using System.Data;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Central;

public sealed class TenantSyncRepository(
    FolhasDbContext dbContext,
    IClock clock) : ISyncRepository
{
    public async Task<SyncCommandResult> ApplyAsync(
        Guid organizationId,
        Guid userId,
        UpsertClientCommand command,
        CancellationToken cancellationToken)
    {
        if (organizationId == Guid.Empty || userId == Guid.Empty || command.OperationId == Guid.Empty)
        {
            return Rejected(command.OperationId);
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);

        var priorOperation = await dbContext.SyncOperations
            .AsNoTracking()
            .SingleOrDefaultAsync(
                operation => operation.OrganizationId == organizationId &&
                    operation.OperationId == command.OperationId,
                cancellationToken);

        if (priorOperation is not null)
        {
            var priorClient = await FindClientAsync(
                organizationId,
                priorOperation.EntityId,
                cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            return new SyncCommandResult(
                command.OperationId,
                SyncCommandStatus.Duplicate,
                priorClient is null ? null : Map(priorClient),
                priorOperation.Status == nameof(SyncCommandStatus.Conflict)
                    ? SyncErrorCodes.Conflict
                    : null);
        }

        var client = await FindClientAsync(organizationId, command.ClientId, cancellationToken);
        SyncCommandResult result;

        try
        {
            if (client is null)
            {
                if (command.ExpectedVersion != 0)
                {
                    result = Conflict(command.OperationId, null);
                }
                else
                {
                    client = new SynchronizedClient(
                        command.ClientId,
                        organizationId,
                        command.DisplayName,
                        userId,
                        clock.UtcNow);
                    dbContext.SynchronizedClients.Add(client);
                    result = Applied(command.OperationId, client);
                }
            }
            else
            {
                client.Apply(
                    command.DisplayName,
                    command.IsActive,
                    command.ExpectedVersion,
                    userId,
                    clock.UtcNow);
                result = Applied(command.OperationId, client);
            }
        }
        catch (ConcurrencyConflictException)
        {
            result = Conflict(command.OperationId, client);
        }
        catch (DomainValidationException)
        {
            result = Rejected(command.OperationId);
        }

        dbContext.SyncOperations.Add(
            new SyncOperation
            {
                OrganizationId = organizationId,
                OperationId = command.OperationId,
                EntityId = command.ClientId,
                Status = result.Status.ToString(),
                ResultVersion = result.Current?.Version ?? 0,
                AppliedAtUtc = clock.UtcNow,
            });

        if (result.Status == SyncCommandStatus.Applied && client is not null)
        {
            dbContext.SyncChanges.Add(
                new SyncChange
                {
                    OrganizationId = organizationId,
                    EntityId = client.Id,
                    EntityType = "client",
                    ChangedAtUtc = clock.UtcNow,
                });
            dbContext.AuditEvents.Add(
                new AuditEvent
                {
                    Id = Guid.NewGuid(),
                    OrganizationId = organizationId,
                    UserId = userId,
                    EntityType = "client",
                    EntityId = client.Id.ToString("D"),
                    Action = client.Version == 1 ? "created" : "updated",
                    Category = "registration",
                    Severity = "information",
                    RedactedDataJson = $"{{\"version\":{client.Version},\"active\":{client.IsActive.ToString().ToLowerInvariant()}}}",
                    TimestampUtc = clock.UtcNow,
                    CorrelationId = command.OperationId,
                });
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return result;
    }

    public async Task<PullSyncResponse> PullAsync(
        Guid organizationId,
        long checkpoint,
        CancellationToken cancellationToken)
    {
        var normalizedCheckpoint = Math.Max(0, checkpoint);
        var changes = await dbContext.SyncChanges
            .AsNoTracking()
            .Where(change => change.OrganizationId == organizationId &&
                change.Checkpoint > normalizedCheckpoint)
            .OrderBy(change => change.Checkpoint)
            .ToArrayAsync(cancellationToken);

        if (changes.Length == 0)
        {
            return new PullSyncResponse(normalizedCheckpoint, []);
        }

        var ids = changes.Select(change => change.EntityId).Distinct().ToArray();
        var clients = await dbContext.SynchronizedClients
            .AsNoTracking()
            .Where(client => client.OrganizationId == organizationId && ids.Contains(client.Id))
            .OrderBy(client => client.DisplayName)
            .Select(client => new ClientSyncItem(
                client.Id,
                client.DisplayName,
                client.IsActive,
                client.Version,
                client.UpdatedAtUtc))
            .ToArrayAsync(cancellationToken);

        return new PullSyncResponse(changes[^1].Checkpoint, clients);
    }

    public Task<long> GetLatestCheckpointAsync(
        Guid organizationId,
        CancellationToken cancellationToken) =>
        dbContext.SyncChanges
            .AsNoTracking()
            .Where(change => change.OrganizationId == organizationId)
            .Select(change => change.Checkpoint)
            .DefaultIfEmpty()
            .MaxAsync(cancellationToken);

    private Task<SynchronizedClient?> FindClientAsync(
        Guid organizationId,
        Guid clientId,
        CancellationToken cancellationToken) =>
        dbContext.SynchronizedClients.SingleOrDefaultAsync(
            item => item.OrganizationId == organizationId && item.Id == clientId,
            cancellationToken);

    private static ClientSyncItem Map(SynchronizedClient client) =>
        new(
            client.Id,
            client.DisplayName,
            client.IsActive,
            client.Version,
            client.UpdatedAtUtc);

    private static SyncCommandResult Applied(Guid operationId, SynchronizedClient client) =>
        new(operationId, SyncCommandStatus.Applied, Map(client), null);

    private static SyncCommandResult Conflict(Guid operationId, SynchronizedClient? client) =>
        new(
            operationId,
            SyncCommandStatus.Conflict,
            client is null ? null : Map(client),
            SyncErrorCodes.Conflict);

    private static SyncCommandResult Rejected(Guid operationId) =>
        new(
            operationId,
            SyncCommandStatus.Rejected,
            null,
            SyncErrorCodes.InvalidCommand);
}
