using FolhasDaMichelly.Contracts.Clients;

namespace FolhasDaMichelly.Application.Clients;

public interface IClientCatalogRepository
{
    Task<ClientSearchResponse> SearchAsync(
        Guid organizationId,
        string? search,
        bool? isActive,
        PersonTypeModel? personType,
        int skip,
        int take,
        CancellationToken cancellationToken);

    Task<ClientDetails?> GetAsync(
        Guid organizationId,
        Guid clientId,
        CancellationToken cancellationToken);

    Task<ClientDetails> CreateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        ClientMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken);

    Task<ClientDetails?> UpdateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        Guid clientId,
        ClientMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken);

    Task<ClientReadinessResponse?> GetReadinessAsync(
        Guid organizationId,
        Guid clientId,
        DateOnly today,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid organizationId,
        Guid? clientId,
        bool includeInactive,
        CancellationToken cancellationToken);

    Task<MessageTemplateModel> CreateTemplateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        MessageTemplateMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken);

    Task<MessageTemplateModel?> UpdateTemplateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        Guid templateId,
        MessageTemplateMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken);

    Task<ClientCatalogTransferDocument> ExportAsync(
        Guid organizationId,
        CancellationToken cancellationToken);

    Task<ClientCatalogImportResult> ImportAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        ClientCatalogImportRequest request,
        Guid correlationId,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<AuditEventModel>> GetClientAuditAsync(
        Guid organizationId,
        Guid clientId,
        int take,
        CancellationToken cancellationToken);

    Task<long> GetLatestCheckpointAsync(
        Guid organizationId,
        CancellationToken cancellationToken);
}

public sealed class CatalogConcurrencyException(Guid entityId, long expected, long actual)
    : Exception($"Catalog entity {entityId} has version {actual}; version {expected} was requested.")
{
    public Guid EntityId { get; } = entityId;
    public long ExpectedVersion { get; } = expected;
    public long ActualVersion { get; } = actual;
}

public sealed class CatalogValidationException(string message) : Exception(message);
