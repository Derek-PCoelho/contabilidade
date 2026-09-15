using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Clients;

public enum PersonType
{
    Individual,
    LegalEntity,
}

public enum ClientIdentifierType
{
    Cnpj,
    CnpjRoot,
    Cpf,
    InternalCode,
    LegalNameAlias,
    Other,
}

public enum ClientIdentifierSemanticRole
{
    PrimaryTaxpayer,
    Employer,
    Establishment,
    AccountingCode,
    LegalNameAlias,
    Other,
}

public enum DeliveryRole
{
    To,
    Cc,
    InternalCopy,
}

public enum ClientPartnerRole
{
    ManagingPartner,
    Partner,
    Administrator,
    LegalRepresentative,
    Other,
}

public sealed record ClientIdentifierDraft(
    Guid Id,
    ClientIdentifierType Type,
    string Value,
    ClientIdentifierSemanticRole SemanticRole,
    int Priority,
    bool IsActive,
    bool IsUniqueWithinOrganization);

public sealed record EstablishmentDraft(
    Guid Id,
    string Cnpj,
    string LegalName,
    string DisplayName,
    string? InternalCode,
    bool IsHeadOffice,
    bool IsActive);

public sealed record RecipientDraft(
    Guid Id,
    Guid? EstablishmentId,
    string DisplayName,
    string Email,
    DeliveryRole DeliveryRole,
    Guid? DocumentTypeId,
    bool IsPrimary,
    bool IsActive,
    DateOnly? ValidFrom,
    DateOnly? ValidTo);

public sealed record ClientPartnerDraft(
    Guid Id,
    string FullName,
    string? Cpf,
    ClientPartnerRole Role,
    bool IsActive,
    string? Email = null);

public sealed record ClientCatalogDraft(
    PersonType PersonType,
    string LegalNameOrFullName,
    string? PreferredName,
    string? InternalCode,
    string PrimaryTaxId,
    bool IsActive,
    Guid? DefaultSubjectTemplateId,
    Guid? DefaultBodyTemplateId,
    string? Notes,
    IReadOnlyList<ClientIdentifierDraft> Identifiers,
    IReadOnlyList<EstablishmentDraft> Establishments,
    IReadOnlyList<RecipientDraft> Recipients,
    IReadOnlyList<ClientPartnerDraft>? Partners = null);

public sealed class Client
{
    private readonly List<ClientIdentifier> identifiers = [];
    private readonly List<Establishment> establishments = [];
    private readonly List<Recipient> recipients = [];
    private readonly List<ClientPartner> partners = [];
    private bool isInitializing;

    private Client()
    {
    }

    public Client(
        Guid id,
        Guid organizationId,
        ClientCatalogDraft draft,
        Guid actorId,
        DateTimeOffset now)
    {
        ValidateIdentifiers(id, organizationId, actorId);
        Id = id;
        OrganizationId = organizationId;
        CreatedBy = actorId;
        CreatedAtUtc = now.ToUniversalTime();
        Version = 0;
        isInitializing = true;
        Apply(draft, 0, actorId, now);
        isInitializing = false;
    }

    public Guid Id { get; private set; }

    public Guid OrganizationId { get; private set; }

    public PersonType PersonType { get; private set; }

    public string LegalNameOrFullName { get; private set; } = string.Empty;

    public string? PreferredName { get; private set; }

    public string? InternalCode { get; private set; }

    public string PrimaryTaxIdNormalized { get; private set; } = string.Empty;

    public bool IsActive { get; private set; }

    public Guid? DefaultSubjectTemplateId { get; private set; }

    public Guid? DefaultBodyTemplateId { get; private set; }

    public string? Notes { get; private set; }

    public Guid CreatedBy { get; private set; }

    public Guid UpdatedBy { get; private set; }

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public long Version { get; private set; }

    public IReadOnlyCollection<ClientIdentifier> Identifiers => identifiers;

    public IReadOnlyCollection<Establishment> Establishments => establishments;

    public IReadOnlyCollection<Recipient> Recipients => recipients;

    public IReadOnlyCollection<ClientPartner> Partners => partners;

    public void Apply(
        ClientCatalogDraft draft,
        long expectedVersion,
        Guid actorId,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(draft);
        if (expectedVersion != Version)
        {
            throw new ConcurrencyConflictException(Id, expectedVersion, Version);
        }

        if (actorId == Guid.Empty)
        {
            throw new DomainValidationException("Actor identifier cannot be empty.");
        }

        PersonType = draft.PersonType;
        LegalNameOrFullName = Required(draft.LegalNameOrFullName, 200, "Legal/full name");
        PreferredName = Optional(draft.PreferredName, 160);
        InternalCode = Optional(draft.InternalCode, 80)?.ToUpperInvariant();
        PrimaryTaxIdNormalized = draft.PersonType == PersonType.LegalEntity
            ? BrazilianRegistration.NormalizeCnpj(draft.PrimaryTaxId)
            : BrazilianRegistration.NormalizeCpf(draft.PrimaryTaxId);
        IsActive = draft.IsActive;
        DefaultSubjectTemplateId = NormalizeOptionalId(draft.DefaultSubjectTemplateId);
        DefaultBodyTemplateId = NormalizeOptionalId(draft.DefaultBodyTemplateId);
        Notes = Optional(draft.Notes, 2000);

        ReconcileIdentifiers(draft.Identifiers);
        ReconcileEstablishments(draft.Establishments);
        ReconcileRecipients(draft.Recipients);
        ReconcilePartners(draft.Partners ?? []);
        ValidateCatalogConsistency();

        UpdatedBy = actorId;
        UpdatedAtUtc = now.ToUniversalTime();
        Version++;
    }

    private void ReconcileIdentifiers(IReadOnlyList<ClientIdentifierDraft> drafts)
    {
        ArgumentNullException.ThrowIfNull(drafts);
        if (!isInitializing)
        {
            EnsureKnownIds(drafts.Select(item => item.Id), identifiers.Select(item => item.Id), "identifier");
        }
        EnsureDistinct(
            drafts.Where(item => item.IsActive)
                .Select(item => $"{item.Type}:{ClientIdentifier.Normalize(item.Type, item.Value)}"),
            "Active identifiers must be unique inside a client.");

        foreach (var existing in identifiers)
        {
            var draft = drafts.SingleOrDefault(item => item.Id == existing.Id);
            if (draft is null)
            {
                existing.Deactivate();
            }
            else
            {
                existing.Apply(draft);
            }
        }

        foreach (var draft in drafts.Where(item => item.Id == Guid.Empty || isInitializing))
        {
            identifiers.Add(new ClientIdentifier(
                draft.Id == Guid.Empty ? Guid.NewGuid() : draft.Id,
                Id,
                OrganizationId,
                draft));
        }
    }

    private void ReconcileEstablishments(IReadOnlyList<EstablishmentDraft> drafts)
    {
        ArgumentNullException.ThrowIfNull(drafts);
        if (PersonType == PersonType.Individual && drafts.Count > 0)
        {
            throw new DomainValidationException("An individual client cannot have establishments.");
        }

        if (!isInitializing)
        {
            EnsureKnownIds(drafts.Select(item => item.Id), establishments.Select(item => item.Id), "establishment");
        }
        EnsureDistinct(
            drafts.Where(item => item.IsActive)
                .Select(item => BrazilianRegistration.NormalizeCnpj(item.Cnpj)),
            "Active establishment CNPJs must be unique inside a client.");
        if (drafts.Count(item => item.IsActive && item.IsHeadOffice) > 1)
        {
            throw new DomainValidationException("A client can have only one active head office.");
        }

        foreach (var existing in establishments)
        {
            var draft = drafts.SingleOrDefault(item => item.Id == existing.Id);
            if (draft is null)
            {
                existing.Deactivate();
            }
            else
            {
                existing.Apply(draft, PrimaryTaxIdNormalized);
            }
        }

        foreach (var draft in drafts.Where(item => item.Id == Guid.Empty || isInitializing))
        {
            establishments.Add(new Establishment(
                draft.Id == Guid.Empty ? Guid.NewGuid() : draft.Id,
                Id,
                OrganizationId,
                draft,
                PrimaryTaxIdNormalized));
        }
    }

    private void ReconcileRecipients(IReadOnlyList<RecipientDraft> drafts)
    {
        ArgumentNullException.ThrowIfNull(drafts);
        if (!isInitializing)
        {
            EnsureKnownIds(drafts.Select(item => item.Id), recipients.Select(item => item.Id), "recipient");
        }
        EnsureDistinct(
            drafts.Where(item => item.IsActive)
                .Select(item =>
                    $"{item.EstablishmentId}:{Recipient.NormalizeEmail(item.Email)}:{item.DeliveryRole}:{item.DocumentTypeId}"),
            "Active recipient routes must be unique inside a client.");

        foreach (var existing in recipients)
        {
            var draft = drafts.SingleOrDefault(item => item.Id == existing.Id);
            if (draft is null)
            {
                existing.Deactivate();
            }
            else
            {
                existing.Apply(draft);
            }
        }

        foreach (var draft in drafts.Where(item => item.Id == Guid.Empty || isInitializing))
        {
            recipients.Add(new Recipient(
                draft.Id == Guid.Empty ? Guid.NewGuid() : draft.Id,
                Id,
                OrganizationId,
                draft));
        }
    }

    private void ReconcilePartners(IReadOnlyList<ClientPartnerDraft> drafts)
    {
        ArgumentNullException.ThrowIfNull(drafts);
        if (PersonType == PersonType.Individual && drafts.Any(item => item.IsActive))
        {
            throw new DomainValidationException("An individual client cannot have partners.");
        }

        if (!isInitializing)
        {
            EnsureKnownIds(drafts.Select(item => item.Id), partners.Select(item => item.Id), "partner");
        }

        var activeCpfs = drafts.Where(item => item.IsActive && !string.IsNullOrWhiteSpace(item.Cpf))
            .Select(item => BrazilianRegistration.NormalizeCpf(item.Cpf!))
            .ToArray();
        EnsureDistinct(activeCpfs, "Active partner CPFs must be unique inside a client.");

        foreach (var existing in partners)
        {
            var draft = drafts.SingleOrDefault(item => item.Id == existing.Id);
            if (draft is null)
            {
                existing.Deactivate();
            }
            else
            {
                existing.Apply(draft);
            }
        }

        foreach (var draft in drafts.Where(item => item.Id == Guid.Empty || isInitializing))
        {
            partners.Add(new ClientPartner(
                draft.Id == Guid.Empty ? Guid.NewGuid() : draft.Id,
                Id,
                OrganizationId,
                draft));
        }
    }

    private void ValidateCatalogConsistency()
    {
        if (PersonType == PersonType.LegalEntity && identifiers.Any(item =>
                item.IsActive && item.Type == ClientIdentifierType.Cpf))
        {
            throw new DomainValidationException(
                "A legal entity cannot use a CPF as a client identifier. Register it as a partner instead.");
        }

        if (PersonType == PersonType.Individual && identifiers.Any(item =>
                item.IsActive && item.Type is ClientIdentifierType.Cnpj or ClientIdentifierType.CnpjRoot))
        {
            throw new DomainValidationException(
                "An individual cannot use a CNPJ as a client identifier.");
        }

        var establishmentIds = establishments.Where(item => item.IsActive)
            .Select(item => item.Id)
            .ToHashSet();
        if (recipients.Any(item =>
                item.IsActive && item.EstablishmentId is not null &&
                !establishmentIds.Contains(item.EstablishmentId.Value)))
        {
            throw new DomainValidationException(
                "An active recipient references an unknown or inactive establishment.");
        }
    }

    private static void ValidateIdentifiers(Guid id, Guid organizationId, Guid actorId)
    {
        if (id == Guid.Empty || organizationId == Guid.Empty || actorId == Guid.Empty)
        {
            throw new DomainValidationException(
                "Client, organization, and actor identifiers cannot be empty.");
        }
    }

    private static void EnsureKnownIds(
        IEnumerable<Guid> requestedIds,
        IEnumerable<Guid> existingIds,
        string entityName)
    {
        var known = existingIds.ToHashSet();
        if (requestedIds.Any(id => id != Guid.Empty && !known.Contains(id)))
        {
            throw new DomainValidationException($"The {entityName} update contains an unknown id.");
        }
    }

    private static void EnsureDistinct(IEnumerable<string> values, string message)
    {
        var items = values.ToArray();
        if (items.Distinct(StringComparer.Ordinal).Count() != items.Length)
        {
            throw new DomainValidationException(message);
        }
    }

    private static string Required(string value, int maximumLength, string fieldName)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length is 0 || normalized.Length > maximumLength)
        {
            throw new DomainValidationException(
                $"{fieldName} must contain between 1 and {maximumLength} characters.");
        }

        return normalized;
    }

    private static string? Optional(string? value, int maximumLength)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrEmpty(normalized))
        {
            return null;
        }

        if (normalized.Length > maximumLength)
        {
            throw new DomainValidationException(
                $"Optional value cannot exceed {maximumLength} characters.");
        }

        return normalized;
    }

    private static Guid? NormalizeOptionalId(Guid? value) =>
        value is null || value == Guid.Empty ? null : value;
}

public sealed class ClientIdentifier
{
    private ClientIdentifier()
    {
    }

    internal ClientIdentifier(
        Guid id,
        Guid clientId,
        Guid organizationId,
        ClientIdentifierDraft draft)
    {
        Id = id;
        ClientId = clientId;
        OrganizationId = organizationId;
        Apply(draft);
    }

    public Guid Id { get; private set; }
    public Guid ClientId { get; private set; }
    public Guid OrganizationId { get; private set; }
    public ClientIdentifierType Type { get; private set; }
    public string ValueNormalized { get; private set; } = string.Empty;
    public ClientIdentifierSemanticRole SemanticRole { get; private set; }
    public int Priority { get; private set; }
    public bool IsActive { get; private set; }
    public bool IsUniqueWithinOrganization { get; private set; }

    internal void Apply(ClientIdentifierDraft draft)
    {
        Type = draft.Type;
        ValueNormalized = Normalize(draft.Type, draft.Value);
        SemanticRole = draft.SemanticRole;
        Priority = Math.Clamp(draft.Priority, 0, 1000);
        IsActive = draft.IsActive;
        IsUniqueWithinOrganization = draft.IsUniqueWithinOrganization;
    }

    internal void Deactivate() => IsActive = false;

    internal static string Normalize(ClientIdentifierType type, string value)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length is 0 or > 200)
        {
            throw new DomainValidationException(
                "Identifier must contain between 1 and 200 characters.");
        }

        return type switch
        {
            ClientIdentifierType.Cnpj => BrazilianRegistration.NormalizeCnpj(normalized),
            ClientIdentifierType.CnpjRoot => NormalizeCnpjRoot(normalized),
            ClientIdentifierType.Cpf => BrazilianRegistration.NormalizeCpf(normalized),
            ClientIdentifierType.InternalCode => normalized.ToUpperInvariant(),
            ClientIdentifierType.LegalNameAlias => NormalizeText(normalized),
            _ => normalized.ToUpperInvariant(),
        };
    }

    private static string NormalizeCnpjRoot(string value)
    {
        if (value.Any(character =>
                !char.IsAsciiDigit(character) &&
                character is not '.' and not '/' and not '-' &&
                !char.IsWhiteSpace(character)))
        {
            throw new DomainValidationException("CNPJ root contains unsupported characters.");
        }

        var digits = new string(value.Where(char.IsAsciiDigit).ToArray());
        if (digits.Length != 8)
        {
            throw new DomainValidationException("CNPJ root must contain exactly 8 digits.");
        }

        return digits;
    }

    private static string NormalizeText(string value) => string.Join(
        ' ',
        value.Normalize().ToUpperInvariant()
            .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
}

public sealed class Establishment
{
    private Establishment()
    {
    }

    internal Establishment(
        Guid id,
        Guid clientId,
        Guid organizationId,
        EstablishmentDraft draft,
        string clientTaxId)
    {
        Id = id;
        ClientId = clientId;
        OrganizationId = organizationId;
        Apply(draft, clientTaxId);
    }

    public Guid Id { get; private set; }
    public Guid ClientId { get; private set; }
    public Guid OrganizationId { get; private set; }
    public string CnpjNormalized { get; private set; } = string.Empty;
    public string CnpjRoot { get; private set; } = string.Empty;
    public string LegalName { get; private set; } = string.Empty;
    public string DisplayName { get; private set; } = string.Empty;
    public string? InternalCode { get; private set; }
    public bool IsHeadOffice { get; private set; }
    public bool IsActive { get; private set; }

    internal void Apply(EstablishmentDraft draft, string clientTaxId)
    {
        CnpjNormalized = BrazilianRegistration.NormalizeCnpj(draft.Cnpj);
        CnpjRoot = BrazilianRegistration.CnpjRoot(CnpjNormalized);
        if (CnpjRoot != BrazilianRegistration.CnpjRoot(clientTaxId))
        {
            throw new DomainValidationException(
                "Establishment CNPJ must share the legal entity client's CNPJ root.");
        }

        LegalName = Required(draft.LegalName, "Establishment legal name");
        DisplayName = Required(draft.DisplayName, "Establishment display name");
        InternalCode = string.IsNullOrWhiteSpace(draft.InternalCode)
            ? null
            : draft.InternalCode.Trim().ToUpperInvariant();
        if (InternalCode?.Length > 80)
        {
            throw new DomainValidationException("Establishment internal code is too long.");
        }

        IsHeadOffice = draft.IsHeadOffice;
        IsActive = draft.IsActive;
    }

    internal void Deactivate() => IsActive = false;

    private static string Required(string value, string fieldName)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length is 0 or > 200)
        {
            throw new DomainValidationException(
                $"{fieldName} must contain between 1 and 200 characters.");
        }

        return normalized;
    }
}

public sealed class Recipient
{
    private Recipient()
    {
    }

    internal Recipient(Guid id, Guid clientId, Guid organizationId, RecipientDraft draft)
    {
        Id = id;
        ClientId = clientId;
        OrganizationId = organizationId;
        Apply(draft);
    }

    public Guid Id { get; private set; }
    public Guid ClientId { get; private set; }
    public Guid OrganizationId { get; private set; }
    public Guid? EstablishmentId { get; private set; }
    public string DisplayName { get; private set; } = string.Empty;
    public string EmailNormalized { get; private set; } = string.Empty;
    public DeliveryRole DeliveryRole { get; private set; }
    public Guid? DocumentTypeId { get; private set; }
    public bool IsPrimary { get; private set; }
    public bool IsActive { get; private set; }
    public DateOnly? ValidFrom { get; private set; }
    public DateOnly? ValidTo { get; private set; }

    public bool IsCurrentlyValid(DateOnly today) => IsActive &&
        (ValidFrom is null || ValidFrom <= today) &&
        (ValidTo is null || ValidTo >= today);

    internal void Apply(RecipientDraft draft)
    {
        EstablishmentId = draft.EstablishmentId is { } id && id != Guid.Empty ? id : null;
        DisplayName = draft.DisplayName?.Trim() ?? string.Empty;
        if (DisplayName.Length is 0 or > 160)
        {
            throw new DomainValidationException(
                "Recipient display name must contain between 1 and 160 characters.");
        }

        EmailNormalized = EmailAddress.Normalize(draft.Email);
        DeliveryRole = draft.DeliveryRole;
        DocumentTypeId = draft.DocumentTypeId is { } typeId && typeId != Guid.Empty
            ? typeId
            : null;
        IsPrimary = draft.IsPrimary;
        IsActive = draft.IsActive;
        ValidFrom = draft.ValidFrom;
        ValidTo = draft.ValidTo;
        if (ValidFrom is not null && ValidTo is not null && ValidFrom > ValidTo)
        {
            throw new DomainValidationException(
                "Recipient validity start cannot be after its end.");
        }
    }

    internal void Deactivate() => IsActive = false;

    internal static string NormalizeEmail(string value) => EmailAddress.Normalize(value);
}

public sealed class ClientPartner
{
    private ClientPartner()
    {
    }

    internal ClientPartner(
        Guid id,
        Guid clientId,
        Guid organizationId,
        ClientPartnerDraft draft)
    {
        Id = id;
        ClientId = clientId;
        OrganizationId = organizationId;
        Apply(draft);
    }

    public Guid Id { get; private set; }
    public Guid ClientId { get; private set; }
    public Guid OrganizationId { get; private set; }
    public string FullName { get; private set; } = string.Empty;
    public string? CpfNormalized { get; private set; }
    public string? EmailNormalized { get; private set; }
    public ClientPartnerRole Role { get; private set; }
    public bool IsActive { get; private set; }

    internal void Apply(ClientPartnerDraft draft)
    {
        FullName = draft.FullName?.Trim() ?? string.Empty;
        if (FullName.Length is 0 or > 200)
        {
            throw new DomainValidationException(
                "Partner full name must contain between 1 and 200 characters.");
        }

        CpfNormalized = string.IsNullOrWhiteSpace(draft.Cpf)
            ? null
            : BrazilianRegistration.NormalizeCpf(draft.Cpf);
        EmailNormalized = string.IsNullOrWhiteSpace(draft.Email)
            ? null
            : EmailAddress.Normalize(draft.Email);
        Role = draft.Role;
        IsActive = draft.IsActive;
    }

    internal void Deactivate() => IsActive = false;
}
