using System.Globalization;
using System.Text;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class CentralClientResolver(
    FolhasDbContext dbContext,
    IAuthenticatedUserAccessor authenticatedUserAccessor) : IClientResolver
{
    public async Task<ClientResolutionResult> ResolveAsync(
        ClientResolutionRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var organizationId = authenticatedUserAccessor.Current.OrganizationId;
        var clients = await dbContext.Clients.AsNoTracking()
            .Include(client => client.Identifiers)
            .Include(client => client.Establishments)
            .AsSplitQuery()
            .Where(client => client.OrganizationId == organizationId)
            .ToArrayAsync(cancellationToken);

        var eligibleTaxFields = request.Fields.Where(field =>
            field.Role is SemanticFieldRole.EmployerTaxId or
                SemanticFieldRole.ClientTaxId or
                SemanticFieldRole.EstablishmentTaxId).ToArray();
        var cnpjFields = eligibleTaxFields.Where(field => Digits(field.Value).Length == 14).ToArray();
        var cpfFields = eligibleTaxFields.Where(field =>
            field.Role == SemanticFieldRole.ClientTaxId && Digits(field.Value).Length == 11).ToArray();
        var nameFields = request.Fields.Where(field =>
            field.Role is SemanticFieldRole.EmployerName or SemanticFieldRole.ClientName).ToArray();
        var internalCodes = request.Fields.Where(field => field.Role == SemanticFieldRole.InternalCode).ToArray();

        foreach (var field in cnpjFields)
        {
            var taxId = Digits(field.Value);
            var exact = clients.Where(client => client.PrimaryTaxIdNormalized == taxId).ToArray();
            if (exact.Length == 1)
            {
                return Result(exact[0], null, ClientResolutionMethod.ExactClientTaxId, .99m, field);
            }

            var establishments = clients.SelectMany(client => client.Establishments
                .Where(establishment => establishment.CnpjNormalized == taxId)
                .Select(establishment => (Client: client, Establishment: establishment))).ToArray();
            if (establishments.Length == 1)
            {
                return Result(
                    establishments[0].Client,
                    establishments[0].Establishment,
                    ClientResolutionMethod.ExactEstablishmentTaxId,
                    .99m,
                    field);
            }
        }

        foreach (var field in cpfFields)
        {
            var taxId = Digits(field.Value);
            var exact = clients.Where(client =>
                client.PersonType == PersonType.Individual &&
                client.PrimaryTaxIdNormalized == taxId).ToArray();
            if (exact.Length == 1)
            {
                return Result(exact[0], null, ClientResolutionMethod.ExactIndividualTaxId, .99m, field);
            }
        }

        foreach (var field in cnpjFields)
        {
            var root = Digits(field.Value)[..8];
            var rootMatches = clients.Where(client =>
                client.PersonType == PersonType.LegalEntity &&
                client.PrimaryTaxIdNormalized.StartsWith(root, StringComparison.Ordinal)).ToArray();
            var coherent = rootMatches.Where(client => nameFields.Any(name =>
                NamesCoherent(client.LegalNameOrFullName, name.Value) ||
                (!string.IsNullOrWhiteSpace(client.PreferredName) && NamesCoherent(client.PreferredName, name.Value))))
                .ToArray();
            if (coherent.Length == 1)
            {
                return Result(coherent[0], null, ClientResolutionMethod.UniqueCnpjRootAndName, .91m, field);
            }

            if (rootMatches.Select(client => client.Id).Distinct().Count() > 1)
            {
                return UnresolvedWithAlternatives(
                    rootMatches,
                    "client.cnpj_root_ambiguous",
                    ClientResolutionMethod.UniqueCnpjRootAndName);
            }
        }

        foreach (var field in internalCodes)
        {
            var exact = clients.Where(client => string.Equals(
                client.InternalCode,
                field.Value.Trim(),
                StringComparison.OrdinalIgnoreCase)).ToArray();
            if (exact.Length == 1)
            {
                return Result(exact[0], null, ClientResolutionMethod.ExactInternalCode, .95m, field);
            }
        }

        foreach (var field in nameFields)
        {
            var normalized = NormalizeName(field.Value);
            var exact = clients.Where(client => NormalizeName(client.LegalNameOrFullName) == normalized).ToArray();
            if (exact.Length == 1)
            {
                return Result(exact[0], null, ClientResolutionMethod.ExactLegalName, .90m, field);
            }

            var aliases = clients.Where(client => client.Identifiers.Any(identifier =>
                identifier.IsActive &&
                identifier.Type == ClientIdentifierType.LegalNameAlias &&
                NormalizeName(identifier.ValueNormalized) == normalized)).ToArray();
            if (aliases.Length == 1)
            {
                return Result(aliases[0], null, ClientResolutionMethod.ExactAlias, .88m, field);
            }
        }

        var fuzzy = nameFields.SelectMany(field => clients.Select(client => new
        {
            Client = client,
            Score = Similarity(NormalizeName(field.Value), NormalizeName(client.LegalNameOrFullName)),
        }))
            .Where(item => item.Score >= .70m)
            .OrderByDescending(item => item.Score)
            .Take(5)
            .Select(item => Candidate(item.Client, ClientResolutionMethod.FuzzySuggestion, item.Score))
            .ToArray();
        return new ClientResolutionResult(
            null,
            null,
            null,
            null,
            ClientResolutionMethod.None,
            0m,
            [],
            fuzzy,
            ["client.not_resolved"]);
    }

    private static ClientResolutionResult Result(
        Client client,
        Establishment? establishment,
        ClientResolutionMethod method,
        decimal confidence,
        RecognizedField field)
    {
        if (!client.IsActive || (establishment is not null && !establishment.IsActive))
        {
            return new ClientResolutionResult(
                null,
                null,
                null,
                null,
                ClientResolutionMethod.None,
                0m,
                [field.Evidence],
                [Candidate(client, method, confidence, establishment)],
                ["client.inactive"]);
        }

        return new ClientResolutionResult(
            client.Id,
            establishment?.Id,
            client.PreferredName ?? client.LegalNameOrFullName,
            Mask(client.PrimaryTaxIdNormalized),
            method,
            confidence,
            [field.Evidence],
            [],
            []);
    }

    private static ClientResolutionResult UnresolvedWithAlternatives(
        IEnumerable<Client> clients,
        string blocker,
        ClientResolutionMethod method) => new(
            null,
            null,
            null,
            null,
            ClientResolutionMethod.None,
            0m,
            [],
            clients.Select(client => Candidate(client, method, .50m)).ToArray(),
            [blocker]);

    private static ClientResolutionCandidate Candidate(
        Client client,
        ClientResolutionMethod method,
        decimal confidence,
        Establishment? establishment = null) => new(
            client.Id,
            establishment?.Id,
            client.PreferredName ?? client.LegalNameOrFullName,
            Mask(client.PrimaryTaxIdNormalized),
            method,
            confidence);

    private static string Digits(string value) => new(value.Where(char.IsAsciiDigit).ToArray());

    private static string Mask(string value) => value.Length switch
    {
        14 => $"{value[..2]}.***.***/****-{value[^2..]}",
        11 => $"***.{value.Substring(3, 3)}.***-{value[^2..]}",
        _ => "***",
    };

    private static bool NamesCoherent(string left, string right)
    {
        var normalizedLeft = NormalizeName(left);
        var normalizedRight = NormalizeName(right);
        return normalizedLeft == normalizedRight ||
            normalizedLeft.Contains(normalizedRight, StringComparison.Ordinal) ||
            normalizedRight.Contains(normalizedLeft, StringComparison.Ordinal) ||
            Similarity(normalizedLeft, normalizedRight) >= .78m;
    }

    private static string NormalizeName(string value)
    {
        var decomposed = value.Normalize(NormalizationForm.FormD);
        var builder = new StringBuilder(decomposed.Length);
        foreach (var character in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark &&
                (char.IsLetterOrDigit(character) || char.IsWhiteSpace(character)))
            {
                builder.Append(char.ToUpperInvariant(character));
            }
        }

        return string.Join(' ', builder.ToString().Split(
            (char[]?)null,
            StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
    }

    private static decimal Similarity(string left, string right)
    {
        if (left.Length == 0 || right.Length == 0)
        {
            return 0m;
        }

        var previous = Enumerable.Range(0, right.Length + 1).ToArray();
        for (var i = 1; i <= left.Length; i++)
        {
            var current = new int[right.Length + 1];
            current[0] = i;
            for (var j = 1; j <= right.Length; j++)
            {
                var cost = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.Min(
                    Math.Min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost);
            }

            previous = current;
        }

        return 1m - ((decimal)previous[^1] / Math.Max(left.Length, right.Length));
    }
}
