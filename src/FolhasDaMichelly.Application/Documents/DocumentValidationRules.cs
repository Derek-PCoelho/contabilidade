using System.Globalization;
using System.Security.Cryptography;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

internal static class ValidationFindingFactory
{
    public static ValidationFinding Create(
        DocumentValidationContext context,
        string code,
        ValidationSeverity severity,
        string message,
        string? fieldKey = null) => new(
            Guid.NewGuid(),
            code,
            severity,
            message,
            fieldKey,
            false,
            FindingResolutionType.None,
            null,
            null,
            context.EvaluatedAtUtc,
            null);
}

public sealed class FileIntegrityValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.file_integrity";

    public async Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(context.Document.LocalPath))
        {
            return [ValidationFindingFactory.Create(
                context,
                "document.file_missing",
                ValidationSeverity.Blocker,
                "O arquivo local não está mais disponível. Selecione novamente o PDF correto.")];
        }

        try
        {
            var info = new FileInfo(context.Document.LocalPath);
            if (info.Length == 0)
            {
                return [ValidationFindingFactory.Create(
                    context,
                    "document.file_empty",
                    ValidationSeverity.Blocker,
                    "O arquivo está vazio.")];
            }

            await using var stream = new FileStream(
                context.Document.LocalPath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                81920,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            var currentHash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken));
            return string.Equals(currentHash, context.Document.Sha256, StringComparison.OrdinalIgnoreCase)
                ? []
                : [ValidationFindingFactory.Create(
                    context,
                    "document.hash_changed",
                    ValidationSeverity.Blocker,
                    "O conteúdo do PDF mudou depois do reconhecimento; a aprovação anterior não é válida.")];
        }
        catch (IOException)
        {
            return [ValidationFindingFactory.Create(
                context,
                "document.file_unreadable",
                ValidationSeverity.Blocker,
                "O arquivo não pôde ser relido para confirmar sua integridade.")];
        }
        catch (UnauthorizedAccessException)
        {
            return [ValidationFindingFactory.Create(
                context,
                "document.file_access_denied",
                ValidationSeverity.Blocker,
                "O acesso ao arquivo foi negado durante a conferência de integridade.")];
        }
    }
}

public sealed class RecognitionValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.recognition";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var findings = new List<ValidationFinding>();
        if (context.Document.DocumentType == RecognizedDocumentType.Unclassified)
        {
            findings.Add(ValidationFindingFactory.Create(
                context,
                "document.unclassified",
                ValidationSeverity.Blocker,
                "O tipo documental não foi reconhecido com segurança."));
        }

        foreach (var finding in context.Document.RecognitionFindings)
        {
            findings.Add(ValidationFindingFactory.Create(
                context,
                finding.Code,
                finding.IsBlocker ? ValidationSeverity.Blocker : ValidationSeverity.Warning,
                finding.Message));
        }

        return Task.FromResult<IReadOnlyList<ValidationFinding>>(findings);
    }
}

public sealed class ClientResolutionValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.client_resolution";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var findings = new List<ValidationFinding>();
        if (!context.Document.ClientId.HasValue)
        {
            findings.Add(ValidationFindingFactory.Create(
                context,
                "client.not_resolved",
                ValidationSeverity.Blocker,
                "O cliente não foi resolvido de forma inequívoca."));
        }

        foreach (var blocker in context.Document.ResolutionBlockers
            .Where(code => !string.Equals(code, "client.not_resolved", StringComparison.Ordinal))
            .Distinct(StringComparer.Ordinal))
        {
            findings.Add(ValidationFindingFactory.Create(
                context,
                blocker,
                ValidationSeverity.Blocker,
                ResolutionMessage(blocker)));
        }

        return Task.FromResult<IReadOnlyList<ValidationFinding>>(findings);
    }

    private static string ResolutionMessage(string code) => code switch
    {
        "client.inactive" => "O cadastro de cliente ou estabelecimento está inativo.",
        "client.cnpj_root_ambiguous" => "A raiz de CNPJ corresponde a mais de um cliente ativo.",
        "client.assignment_changed" => "O cadastro passou a indicar outro cliente. Confira e confirme a associação manualmente.",
        "client.resolution_offline" => "A resolução autenticada do cliente está indisponível offline.",
        "client.resolution_authentication_required" => "Autentique-se para resolver o cliente.",
        _ => "O cliente não pôde ser confirmado automaticamente. Confira o cadastro e os dados reconhecidos.",
    };
}

public sealed class ProfileRequiredFieldsValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.required_fields";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (context.Profile is null)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([
                ValidationFindingFactory.Create(
                    context,
                    "profile.validation_missing",
                    ValidationSeverity.Blocker,
                    "Não há perfil de validação publicado para este tipo documental."),
            ]);
        }

        var findings = context.Profile.RequiredRoles
            .Where(role => !context.Document.Fields.Any(field =>
                field.Role == role && !string.IsNullOrWhiteSpace(field.Value)))
            .Select(role => ValidationFindingFactory.Create(
                context,
                $"field.required.{role.ToString().ToLowerInvariant()}",
                ValidationSeverity.Error,
                $"O campo obrigatório {role} não foi extraído.",
                role.ToString()))
            .ToArray();
        return Task.FromResult<IReadOnlyList<ValidationFinding>>(findings);
    }
}

public sealed class PeriodValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.period";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (context.Profile?.RequirePeriod == true &&
            context.Document.Period.Kind == DocumentPeriodKind.Unknown)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([
                ValidationFindingFactory.Create(
                    context,
                    "period.missing_or_invalid",
                    ValidationSeverity.Error,
                    "A competência ou o período obrigatório não pôde ser normalizado.",
                    "Period"),
            ]);
        }

        if (context.Document.Period.StartDate.HasValue &&
            context.Document.Period.EndDate.HasValue &&
            context.Document.Period.EndDate < context.Document.Period.StartDate)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([
                ValidationFindingFactory.Create(
                    context,
                    "period.range_inverted",
                    ValidationSeverity.Blocker,
                    "A data final do período é anterior à data inicial.",
                    "Period"),
            ]);
        }

        return Task.FromResult<IReadOnlyList<ValidationFinding>>([]);
    }
}

public sealed class AmountValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.amount";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (context.Profile?.RequirePositiveAmount != true)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([]);
        }

        var amountField = context.Document.Fields.FirstOrDefault(field =>
            field.Role == SemanticFieldRole.TotalAmount);
        if (amountField is null || !decimal.TryParse(
                amountField.Value,
                NumberStyles.Number,
                CultureInfo.InvariantCulture,
                out var amount))
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([]);
        }

        if (amount < 0)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([
                ValidationFindingFactory.Create(
                    context,
                    "amount.negative_unexpected",
                    ValidationSeverity.Blocker,
                    "O total extraído é negativo em um perfil que exige valor positivo.",
                    amountField.Name),
            ]);
        }

        if (amount == 0)
        {
            return Task.FromResult<IReadOnlyList<ValidationFinding>>([
                ValidationFindingFactory.Create(
                    context,
                    "amount.zero_unexpected",
                    ValidationSeverity.Error,
                    "O total extraído é zero e exige revisão contábil.",
                    amountField.Name),
            ]);
        }

        return Task.FromResult<IReadOnlyList<ValidationFinding>>([]);
    }
}

public sealed class EmployerRootConsistencyValidationRule : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.employer_roots";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var roots = context.Document.Fields
            .Where(field => field.Role is SemanticFieldRole.EmployerTaxId or SemanticFieldRole.EstablishmentTaxId)
            .Select(field => Digits(field.Value))
            .Where(value => value.Length == 14)
            .Select(value => value[..8])
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        return Task.FromResult<IReadOnlyList<ValidationFinding>>(roots.Length > 1
            ? [ValidationFindingFactory.Create(
                context,
                "client.multiple_employer_roots",
                ValidationSeverity.Blocker,
                "O documento contém empregadores de raízes CNPJ diferentes.")]
            : []);
    }

    private static string Digits(string value) => new(value.Where(char.IsAsciiDigit).ToArray());
}

public sealed class DueDateValidationRule(DocumentReviewOptions options)
    : IValidationRule<DocumentValidationContext>
{
    public string Code => "document.due_date";

    public Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        DocumentValidationContext context,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult<IReadOnlyList<ValidationFinding>>(
            options.PastDueDateProducesWarning &&
            context.Document.Period.DueDate is { } dueDate &&
            dueDate < context.AccountingDate
                ? [ValidationFindingFactory.Create(
                    context,
                    "due_date.past",
                    ValidationSeverity.Warning,
                    "O vencimento informado no documento já passou; confirme a situação antes de prosseguir.",
                    "DueDate")]
                : []);
    }
}
