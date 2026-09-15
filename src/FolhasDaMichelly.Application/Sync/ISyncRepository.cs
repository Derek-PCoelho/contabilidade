using FolhasDaMichelly.Contracts.Sync;

namespace FolhasDaMichelly.Application.Sync;

public interface ISyncRepository
{
    Task<SyncCommandResult> ApplyAsync(
        Guid organizationId,
        Guid userId,
        UpsertClientCommand command,
        CancellationToken cancellationToken);

    Task<PullSyncResponse> PullAsync(
        Guid organizationId,
        long checkpoint,
        CancellationToken cancellationToken);

    Task<long> GetLatestCheckpointAsync(
        Guid organizationId,
        CancellationToken cancellationToken);
}

public interface ISyncChangePublisher
{
    Task PublishAsync(
        Guid organizationId,
        SyncNotification notification,
        CancellationToken cancellationToken);
}
