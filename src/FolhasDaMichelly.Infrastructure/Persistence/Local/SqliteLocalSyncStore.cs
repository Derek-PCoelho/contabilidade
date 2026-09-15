using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class SqliteLocalSyncStore(
    LocalCacheDbContext dbContext,
    IClock clock) : ILocalSyncStore
{
    private const int SingletonStateId = 1;

    public Task InitializeAsync(CancellationToken cancellationToken) =>
        dbContext.Database.MigrateAsync(cancellationToken);

    public async Task EnqueueAsync(
        UpsertClientCommand command,
        CancellationToken cancellationToken)
    {
        if (await dbContext.OfflineOperations.AnyAsync(
                item => item.OperationId == command.OperationId,
                cancellationToken))
        {
            return;
        }

        dbContext.OfflineOperations.Add(
            new OfflineSyncOperation
            {
                OperationId = command.OperationId,
                ClientId = command.ClientId,
                DisplayName = command.DisplayName,
                IsActive = command.IsActive,
                ExpectedVersion = command.ExpectedVersion,
                NextAttemptAtUtc = clock.UtcNow,
                CreatedAtUtc = clock.UtcNow,
            });
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<PendingSyncOperation>> GetReadyOperationsAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken) =>
        await dbContext.OfflineOperations
            .AsNoTracking()
            .Where(item => !item.IsConflicted && item.NextAttemptAtUtc <= now)
            .OrderBy(item => item.CreatedAtUtc)
            .Select(
                item => new PendingSyncOperation(
                    new UpsertClientCommand(
                        item.OperationId,
                        item.ClientId,
                        item.DisplayName,
                        item.IsActive,
                        item.ExpectedVersion),
                    item.AttemptCount,
                    item.NextAttemptAtUtc,
                    item.IsConflicted))
            .ToArrayAsync(cancellationToken);

    public async Task MarkCompletedAsync(
        Guid operationId,
        CancellationToken cancellationToken)
    {
        var operation = await dbContext.OfflineOperations.FindAsync([operationId], cancellationToken);
        if (operation is not null)
        {
            dbContext.OfflineOperations.Remove(operation);
            await dbContext.SaveChangesAsync(cancellationToken);
        }
    }

    public async Task MarkConflictAsync(
        Guid operationId,
        ClientSyncItem? current,
        CancellationToken cancellationToken)
    {
        var operation = await dbContext.OfflineOperations.FindAsync([operationId], cancellationToken);
        if (operation is not null)
        {
            operation.IsConflicted = true;
            operation.LastErrorCode = SyncErrorCodes.Conflict;
        }

        if (current is not null)
        {
            await UpsertCacheAsync(current, cancellationToken);
        }

        await dbContext.SaveChangesAsync(cancellationToken);
    }

    public async Task ScheduleRetryAsync(
        Guid operationId,
        DateTimeOffset nextAttemptAtUtc,
        CancellationToken cancellationToken)
    {
        var operation = await dbContext.OfflineOperations.FindAsync([operationId], cancellationToken);
        if (operation is not null)
        {
            operation.AttemptCount++;
            operation.NextAttemptAtUtc = nextAttemptAtUtc.ToUniversalTime();
            await dbContext.SaveChangesAsync(cancellationToken);
        }
    }

    public async Task<long> GetCheckpointAsync(CancellationToken cancellationToken) =>
        await dbContext.SyncState
            .AsNoTracking()
            .Where(item => item.Id == SingletonStateId)
            .Select(item => item.Checkpoint)
            .SingleOrDefaultAsync(cancellationToken);

    public async Task ApplyPullAsync(
        PullSyncResponse response,
        CancellationToken cancellationToken)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(cancellationToken);
        foreach (var client in response.Clients)
        {
            await UpsertCacheAsync(client, cancellationToken);
        }

        var state = await dbContext.SyncState.FindAsync([SingletonStateId], cancellationToken);
        if (state is null)
        {
            dbContext.SyncState.Add(
                new LocalSyncState
                {
                    Id = SingletonStateId,
                    Checkpoint = response.Checkpoint,
                });
        }
        else
        {
            state.Checkpoint = Math.Max(state.Checkpoint, response.Checkpoint);
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<ClientSyncItem>> GetClientsAsync(
        CancellationToken cancellationToken) =>
        await dbContext.Clients
            .AsNoTracking()
            .OrderBy(item => item.DisplayName)
            .Select(
                item => new ClientSyncItem(
                    item.Id,
                    item.DisplayName,
                    item.IsActive,
                    item.Version,
                    item.UpdatedAtUtc))
            .ToArrayAsync(cancellationToken);

    private async Task UpsertCacheAsync(
        ClientSyncItem item,
        CancellationToken cancellationToken)
    {
        var cached = await dbContext.Clients.FindAsync([item.Id], cancellationToken);
        if (cached is null)
        {
            dbContext.Clients.Add(
                new CachedClient
                {
                    Id = item.Id,
                    DisplayName = item.DisplayName,
                    IsActive = item.IsActive,
                    Version = item.Version,
                    UpdatedAtUtc = item.UpdatedAtUtc,
                });
            return;
        }

        if (item.Version >= cached.Version)
        {
            cached.DisplayName = item.DisplayName;
            cached.IsActive = item.IsActive;
            cached.Version = item.Version;
            cached.UpdatedAtUtc = item.UpdatedAtUtc;
        }
    }
}
