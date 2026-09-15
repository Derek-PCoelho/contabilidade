using FolhasDaMichelly.Contracts.Clients;

namespace FolhasDaMichelly.Application.Clients;

public interface IClientCatalogService
{
    Task<ClientSearchResponse> SearchAsync(
        string? search,
        bool? isActive,
        PersonTypeModel? personType,
        CancellationToken cancellationToken);

    Task<ClientDetails?> GetAsync(Guid clientId, CancellationToken cancellationToken);

    Task<ClientDetails> SaveAsync(
        Guid? clientId,
        ClientMutationRequest request,
        CancellationToken cancellationToken);

    Task<ClientReadinessResponse?> GetReadinessAsync(
        Guid clientId,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid? clientId,
        bool includeInactive,
        CancellationToken cancellationToken);

    Task<MessageTemplateModel> SaveTemplateAsync(
        Guid? templateId,
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken);

    Task<ClientCatalogTransferDocument> ExportAsync(CancellationToken cancellationToken);

    Task<ClientCatalogImportResult> ImportAsync(
        ClientCatalogImportRequest request,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<AuditEventModel>> GetAuditAsync(
        Guid clientId,
        CancellationToken cancellationToken);
}

public interface IClientCatalogCache
{
    Task<ClientDetails?> GetClientAsync(Guid clientId, CancellationToken cancellationToken);

    Task StoreClientAsync(ClientDetails client, CancellationToken cancellationToken);

    Task StoreTemplatesAsync(
        IReadOnlyList<MessageTemplateModel> templates,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid? clientId,
        CancellationToken cancellationToken);
}
