using FolhasDaMichelly.Contracts.Clients;

namespace FolhasDaMichelly.Application.Clients;

public sealed record LocalCatalogArchiveResult(
    Guid EntityId,
    long ArchivedVersion,
    DateTimeOffset ArchivedAtUtc);

public interface ILocalClientCatalogMaintenance
{
    Task<ClientDetails> SetClientActiveAsync(
        Guid clientId,
        long expectedVersion,
        bool isActive,
        CancellationToken cancellationToken);

    Task<MessageTemplateModel> SetTemplateActiveAsync(
        Guid templateId,
        long expectedVersion,
        bool isActive,
        CancellationToken cancellationToken);

    Task<LocalCatalogArchiveResult> ArchiveClientAsync(
        Guid clientId,
        long expectedVersion,
        CancellationToken cancellationToken);

    Task<LocalCatalogArchiveResult> ArchiveTemplateAsync(
        Guid templateId,
        long expectedVersion,
        CancellationToken cancellationToken);
}
