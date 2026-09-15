using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Contracts.Sync;

namespace FolhasDaMichelly.Application.Sync;

public interface ISyncService
{
    Task<SyncCycleResult> SynchronizeAsync(CancellationToken cancellationToken);
}

public sealed record SyncCycleResult(
    SyncCycleStatus Status,
    int CommandsApplied,
    int Conflicts,
    int PendingRetries,
    int CachedClients,
    long Checkpoint);

public enum SyncCycleStatus
{
    Synchronized,
    Offline,
    Conflict,
}

public sealed class SyncService(
    ILocalSyncStore localStore,
    ISyncTransport transport,
    IClock clock) : ISyncService
{
    private static readonly TimeSpan MaximumBackoff = TimeSpan.FromMinutes(5);

    public async Task<SyncCycleResult> SynchronizeAsync(CancellationToken cancellationToken)
    {
        await localStore.InitializeAsync(cancellationToken);
        var ready = await localStore.GetReadyOperationsAsync(clock.UtcNow, cancellationToken);
        var applied = 0;
        var conflicts = 0;

        if (ready.Count > 0)
        {
            PushSyncResponse pushed;
            try
            {
                pushed = await transport.PushAsync(
                    new PushSyncRequest(ready.Select(item => item.Command).ToArray()),
                    cancellationToken);
            }
            catch (HttpRequestException)
            {
                await ScheduleRetriesAsync(ready, cancellationToken);
                return await BuildResultAsync(
                    SyncCycleStatus.Offline,
                    applied,
                    conflicts,
                    ready.Count,
                    cancellationToken);
            }

            foreach (var result in pushed.Results)
            {
                switch (result.Status)
                {
                    case SyncCommandStatus.Applied:
                    case SyncCommandStatus.Duplicate:
                        await localStore.MarkCompletedAsync(result.OperationId, cancellationToken);
                        applied++;
                        break;
                    case SyncCommandStatus.Conflict:
                        await localStore.MarkConflictAsync(
                            result.OperationId,
                            result.Current,
                            cancellationToken);
                        conflicts++;
                        break;
                    case SyncCommandStatus.Rejected:
                        await localStore.MarkConflictAsync(
                            result.OperationId,
                            result.Current,
                            cancellationToken);
                        conflicts++;
                        break;
                    default:
                        throw new InvalidOperationException(
                            $"Unknown sync result status: {result.Status}.");
                }
            }
        }

        try
        {
            var checkpoint = await localStore.GetCheckpointAsync(cancellationToken);
            var pulled = await transport.PullAsync(checkpoint, cancellationToken);
            await localStore.ApplyPullAsync(pulled, cancellationToken);
        }
        catch (HttpRequestException)
        {
            return await BuildResultAsync(
                SyncCycleStatus.Offline,
                applied,
                conflicts,
                0,
                cancellationToken);
        }

        return await BuildResultAsync(
            conflicts > 0 ? SyncCycleStatus.Conflict : SyncCycleStatus.Synchronized,
            applied,
            conflicts,
            0,
            cancellationToken);
    }

    private async Task ScheduleRetriesAsync(
        IReadOnlyList<PendingSyncOperation> operations,
        CancellationToken cancellationToken)
    {
        foreach (var operation in operations)
        {
            var exponent = Math.Min(operation.AttemptCount, 8);
            var seconds = Math.Min(Math.Pow(2, exponent), MaximumBackoff.TotalSeconds);
            var deterministicJitter = operation.Command.OperationId.GetHashCode() & 0x3FF;
            var delay = TimeSpan.FromSeconds(seconds) + TimeSpan.FromMilliseconds(deterministicJitter);
            await localStore.ScheduleRetryAsync(
                operation.Command.OperationId,
                clock.UtcNow.Add(delay),
                cancellationToken);
        }
    }

    private async Task<SyncCycleResult> BuildResultAsync(
        SyncCycleStatus status,
        int applied,
        int conflicts,
        int pendingRetries,
        CancellationToken cancellationToken)
    {
        var clients = await localStore.GetClientsAsync(cancellationToken);
        var checkpoint = await localStore.GetCheckpointAsync(cancellationToken);
        return new SyncCycleResult(
            status,
            applied,
            conflicts,
            pendingRetries,
            clients.Count,
            checkpoint);
    }
}
