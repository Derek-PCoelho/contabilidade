using System.Text.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class SqliteDispatchWorkflowStore(LocalCacheDbContext dbContext)
    : IDispatchWorkflowStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<DispatchWorkspace> LoadAsync(
        string scopeKey,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(scopeKey);
        var batches = await dbContext.ProcessingBatches.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.UpdatedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var items = await dbContext.DispatchItems.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.UpdatedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var attempts = await dbContext.DeliveryAttempts.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.StartedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var audits = await dbContext.DispatchAudit.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.TimestampUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return new DispatchWorkspace(
            scopeKey,
            batches.Select(Deserialize<ProcessingBatch>).ToArray(),
            items.Select(Deserialize<DispatchItem>).ToArray(),
            attempts.Select(Deserialize<DeliveryAttempt>).ToArray(),
            audits.Select(Deserialize<DispatchAuditEvent>).ToArray());
    }

    public async Task SaveAsync(
        DispatchWorkspace workspace,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(workspace);
        ArgumentException.ThrowIfNullOrWhiteSpace(workspace.ScopeKey);
        await using var transaction = await dbContext.Database.BeginTransactionAsync(cancellationToken);
        await dbContext.ProcessingBatches
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ExecuteDeleteAsync(cancellationToken);
        await dbContext.DispatchItems
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ExecuteDeleteAsync(cancellationToken);
        await dbContext.DeliveryAttempts
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ExecuteDeleteAsync(cancellationToken);
        dbContext.ProcessingBatches.AddRange(workspace.Batches.Select(batch => new ProcessingBatchRecord
        {
            ScopeKey = workspace.ScopeKey,
            BatchId = batch.Id,
            State = batch.State.ToString(),
            OperationMode = batch.OperationMode.ToString(),
            JsonPayload = JsonSerializer.Serialize(batch, SerializerOptions),
            UpdatedAtUtc = batch.UpdatedAtUtc,
        }));
        dbContext.DispatchItems.AddRange(workspace.Items.Select(item => new DispatchItemRecord
        {
            ScopeKey = workspace.ScopeKey,
            DispatchItemId = item.Id,
            BatchId = item.BatchId,
            GroupId = item.GroupId,
            State = item.State.ToString(),
            DispatchFingerprint = item.Message?.DispatchFingerprint ?? string.Empty,
            JsonPayload = JsonSerializer.Serialize(item, SerializerOptions),
            UpdatedAtUtc = item.UpdatedAtUtc,
        }));
        dbContext.DeliveryAttempts.AddRange(workspace.Attempts.Select(attempt => new DeliveryAttemptRecord
        {
            ScopeKey = workspace.ScopeKey,
            AttemptId = attempt.Id,
            DispatchItemId = attempt.DispatchItemId,
            State = attempt.State.ToString(),
            IdempotencyKey = attempt.IdempotencyKey,
            JsonPayload = JsonSerializer.Serialize(attempt, SerializerOptions),
            StartedAtUtc = attempt.StartedAtUtc,
        }));

        var existingAuditIds = await dbContext.DispatchAudit.AsNoTracking()
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .Select(record => record.EventId)
            .ToHashSetAsync(cancellationToken);
        dbContext.DispatchAudit.AddRange(workspace.AuditEvents
            .Where(item => !existingAuditIds.Contains(item.Id))
            .Select(item => new DispatchAuditRecord
            {
                ScopeKey = workspace.ScopeKey,
                EventId = item.Id,
                Action = item.Action,
                DispatchItemId = item.DispatchItemId,
                JsonPayload = JsonSerializer.Serialize(item, SerializerOptions),
                TimestampUtc = item.TimestampUtc,
            }));
        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        dbContext.ChangeTracker.Clear();
    }

    private static T Deserialize<T>(string json) =>
        JsonSerializer.Deserialize<T>(json, SerializerOptions)
        ?? throw new InvalidOperationException($"Invalid persisted {typeof(T).Name} payload.");
}
