using FolhasDaMichelly.Contracts.Sync;

namespace FolhasDaMichelly.Application.Sync;

public interface ILocalSyncStore
{
    Task InitializeAsync(CancellationToken cancellationToken);

    Task EnqueueAsync(UpsertClientCommand command, CancellationToken cancellationToken);

    Task<IReadOnlyList<PendingSyncOperation>> GetReadyOperationsAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken);

    Task MarkCompletedAsync(Guid operationId, CancellationToken cancellationToken);

    Task MarkConflictAsync(
        Guid operationId,
        ClientSyncItem? current,
        CancellationToken cancellationToken);

    Task ScheduleRetryAsync(
        Guid operationId,
        DateTimeOffset nextAttemptAtUtc,
        CancellationToken cancellationToken);

    Task<long> GetCheckpointAsync(CancellationToken cancellationToken);

    Task ApplyPullAsync(PullSyncResponse response, CancellationToken cancellationToken);

    Task<IReadOnlyList<ClientSyncItem>> GetClientsAsync(CancellationToken cancellationToken);
}

public sealed record PendingSyncOperation(
    UpsertClientCommand Command,
    int AttemptCount,
    DateTimeOffset NextAttemptAtUtc,
    bool IsConflicted);

public interface ISyncTransport
{
    Task<PushSyncResponse> PushAsync(
        PushSyncRequest request,
        CancellationToken cancellationToken);

    Task<PullSyncResponse> PullAsync(long checkpoint, CancellationToken cancellationToken);
}

public interface ISyncNotificationClient : IAsyncDisposable
{
    event Func<SyncNotification, Task>? ChangeDetected;

    Task StartAsync(CancellationToken cancellationToken);

    Task StopAsync(CancellationToken cancellationToken);
}
