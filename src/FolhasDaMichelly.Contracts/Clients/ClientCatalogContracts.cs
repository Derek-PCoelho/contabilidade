namespace FolhasDaMichelly.Contracts.Clients;

public enum PersonTypeModel
{
    Individual,
    LegalEntity,
}

public enum ClientIdentifierTypeModel
{
    Cnpj,
    CnpjRoot,
    Cpf,
    InternalCode,
    LegalNameAlias,
    Other,
}

public enum ClientIdentifierSemanticRoleModel
{
    PrimaryTaxpayer,
    Employer,
    Establishment,
    AccountingCode,
    LegalNameAlias,
    Other,
}

public enum DeliveryRoleModel
{
    To,
    Cc,
    InternalCopy,
}

public enum ClientPartnerRoleModel
{
    ManagingPartner,
    Partner,
    Administrator,
    LegalRepresentative,
    Other,
}

public enum SignatureModeModel
{
    None,
    User,
    Organization,
}

public sealed record ClientIdentifierModel(
    Guid Id,
    ClientIdentifierTypeModel Type,
    string Value,
    ClientIdentifierSemanticRoleModel SemanticRole,
    int Priority,
    bool IsActive,
    bool IsUniqueWithinOrganization);

public sealed record EstablishmentModel(
    Guid Id,
    string Cnpj,
    string LegalName,
    string DisplayName,
    string? InternalCode,
    bool IsHeadOffice,
    bool IsActive);

public sealed record RecipientModel(
    Guid Id,
    Guid? EstablishmentId,
    string DisplayName,
    string Email,
    DeliveryRoleModel DeliveryRole,
    Guid? DocumentTypeId,
    bool IsPrimary,
    bool IsActive,
    DateOnly? ValidFrom,
    DateOnly? ValidTo);

public sealed record ClientPartnerModel(
    Guid Id,
    string FullName,
    string? Cpf,
    ClientPartnerRoleModel Role,
    bool IsActive,
    string? Email = null);

public sealed record ClientMutationRequest(
    long ExpectedVersion,
    PersonTypeModel PersonType,
    string LegalNameOrFullName,
    string? PreferredName,
    string? InternalCode,
    string PrimaryTaxId,
    bool IsActive,
    Guid? DefaultSubjectTemplateId,
    Guid? DefaultBodyTemplateId,
    string? Notes,
    IReadOnlyList<ClientIdentifierModel> Identifiers,
    IReadOnlyList<EstablishmentModel> Establishments,
    IReadOnlyList<RecipientModel> Recipients,
    IReadOnlyList<ClientPartnerModel>? Partners = null);

public sealed record ClientListItem(
    Guid Id,
    PersonTypeModel PersonType,
    string DisplayName,
    string PrimaryTaxIdMasked,
    string? InternalCode,
    bool IsActive,
    long Version,
    int EstablishmentCount,
    int RecipientCount,
    DateTimeOffset UpdatedAtUtc);

public sealed record ClientDetails(
    Guid Id,
    PersonTypeModel PersonType,
    string LegalNameOrFullName,
    string? PreferredName,
    string? InternalCode,
    string PrimaryTaxId,
    bool IsActive,
    Guid? DefaultSubjectTemplateId,
    Guid? DefaultBodyTemplateId,
    string? Notes,
    long Version,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc,
    IReadOnlyList<ClientIdentifierModel> Identifiers,
    IReadOnlyList<EstablishmentModel> Establishments,
    IReadOnlyList<RecipientModel> Recipients,
    IReadOnlyList<ClientPartnerModel>? Partners = null);

public sealed record ClientSearchResponse(
    IReadOnlyList<ClientListItem> Items,
    int Total,
    int Skip,
    int Take);

public sealed record ClientReadinessResponse(
    Guid ClientId,
    bool IsEligible,
    IReadOnlyList<string> BlockCodes);

public sealed record MessageTemplateMutationRequest(
    long ExpectedVersion,
    Guid? ClientId,
    Guid? DocumentTypeId,
    string Name,
    string SubjectTemplate,
    string BodyTemplate,
    SignatureModeModel SignatureMode,
    bool IsDefault,
    bool IsActive);

public sealed record MessageTemplateModel(
    Guid Id,
    Guid? ClientId,
    Guid? DocumentTypeId,
    string Name,
    string SubjectTemplate,
    string BodyTemplate,
    SignatureModeModel SignatureMode,
    bool IsDefault,
    bool IsActive,
    long Version,
    DateTimeOffset UpdatedAtUtc);

public sealed record ClientCatalogTransferDocument(
    int FormatVersion,
    DateTimeOffset ExportedAtUtc,
    IReadOnlyList<ClientDetails> Clients,
    IReadOnlyList<MessageTemplateModel> Templates);

public sealed record ClientCatalogImportRequest(
    bool DryRun,
    bool OverwriteExisting,
    ClientCatalogTransferDocument Document);

public sealed record ClientCatalogImportResult(
    bool DryRun,
    int ClientsCreated,
    int ClientsUpdated,
    int TemplatesCreated,
    int TemplatesUpdated,
    IReadOnlyList<string> Warnings);

public sealed record AuditEventModel(
    Guid Id,
    string EntityType,
    string EntityId,
    string Action,
    string Category,
    string Severity,
    string RedactedDataJson,
    DateTimeOffset TimestampUtc,
    Guid CorrelationId);
