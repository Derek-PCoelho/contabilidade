using System.Text.Json;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class SqliteDocumentReviewStore(LocalCacheDbContext dbContext)
    : IDocumentReviewStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<DocumentReviewWorkspace> LoadAsync(
        string scopeKey,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(scopeKey);
        var documents = await dbContext.DocumentReviews.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.UpdatedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var groups = await dbContext.DocumentReviewGroups.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.UpdatedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var audits = await dbContext.DocumentReviewAudit.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.TimestampUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return new DocumentReviewWorkspace(
            scopeKey,
            documents.Select(Deserialize<ReviewDocument>).ToArray(),
            groups.Select(Deserialize<DocumentDispatchGroup>).ToArray(),
            audits.Select(Deserialize<ReviewAuditEvent>).ToArray());
    }

    public async Task SaveAsync(
        DocumentReviewWorkspace workspace,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(workspace);
        ArgumentException.ThrowIfNullOrWhiteSpace(workspace.ScopeKey);
        var ownsTransaction = dbContext.Database.CurrentTransaction is null;
        await using var transaction = ownsTransaction
            ? await dbContext.Database.BeginTransactionAsync(cancellationToken)
            : null;
        await dbContext.DocumentReviews
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ExecuteDeleteAsync(cancellationToken);
        await dbContext.DocumentReviewGroups
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ExecuteDeleteAsync(cancellationToken);
        dbContext.DocumentReviews.AddRange(workspace.Documents.Select(document =>
            new DocumentReviewRecord
            {
                ScopeKey = workspace.ScopeKey,
                DocumentId = document.Id,
                Sha256 = document.Sha256,
                SemanticDuplicateKey = document.SemanticDuplicateKey,
                State = document.State.ToString(),
                JsonPayload = JsonSerializer.Serialize(document, SerializerOptions),
                UpdatedAtUtc = document.ValidatedAtUtc,
            }));
        dbContext.DocumentReviewGroups.AddRange(workspace.Groups.Select(group =>
            new DocumentReviewGroupRecord
            {
                ScopeKey = workspace.ScopeKey,
                GroupId = group.Id,
                ClientId = group.ClientId,
                PeriodKey = group.PeriodKey,
                State = group.State.ToString(),
                JsonPayload = JsonSerializer.Serialize(group, SerializerOptions),
                UpdatedAtUtc = group.UpdatedAtUtc,
            }));

        var existingAuditIds = await dbContext.DocumentReviewAudit.AsNoTracking()
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .Select(record => record.EventId)
            .ToHashSetAsync(cancellationToken);
        dbContext.DocumentReviewAudit.AddRange(workspace.AuditEvents
            .Where(item => !existingAuditIds.Contains(item.Id))
            .Select(item => new DocumentReviewAuditRecord
            {
                ScopeKey = workspace.ScopeKey,
                EventId = item.Id,
                Action = item.Action,
                DocumentId = item.DocumentId,
                GroupId = item.GroupId,
                JsonPayload = JsonSerializer.Serialize(item, SerializerOptions),
                TimestampUtc = item.TimestampUtc,
            }));
        await dbContext.SaveChangesAsync(cancellationToken);
        if (transaction is not null)
        {
            await transaction.CommitAsync(cancellationToken);
            dbContext.ChangeTracker.Clear();
        }
    }

    private static T Deserialize<T>(string json) =>
        JsonSerializer.Deserialize<T>(json, SerializerOptions)
        ?? throw new InvalidOperationException($"Invalid persisted {typeof(T).Name} payload.");
}
