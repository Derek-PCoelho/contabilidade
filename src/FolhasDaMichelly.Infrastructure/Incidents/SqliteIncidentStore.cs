using System.Text.Json;
using FolhasDaMichelly.Application.Incidents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Incidents;

public sealed class SqliteIncidentStore(LocalCacheDbContext dbContext) : IIncidentStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<IncidentWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(scopeKey);
        var incidents = await dbContext.Incidents.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.DetectedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        var audits = await dbContext.IncidentAudit.AsNoTracking()
            .Where(record => record.ScopeKey == scopeKey)
            .OrderBy(record => record.TimestampUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return new IncidentWorkspace(
            scopeKey,
            incidents.Select(Deserialize<IncidentRecord>).ToArray(),
            audits.Select(Deserialize<IncidentAuditEvent>).ToArray());
    }

    public async Task SaveAsync(IncidentWorkspace workspace, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(workspace);
        await using var transaction = await dbContext.Database.BeginTransactionAsync(cancellationToken);
        var existingIncidents = await dbContext.Incidents
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .ToDictionaryAsync(record => record.IncidentId, cancellationToken);
        foreach (var incident in workspace.Incidents)
        {
            if (incident.ScopeKey != workspace.ScopeKey)
            {
                throw new InvalidOperationException("Incident scope cannot cross workspace boundaries.");
            }

            if (!existingIncidents.TryGetValue(incident.Id, out var record))
            {
                record = new IncidentRecordEntity
                {
                    ScopeKey = workspace.ScopeKey,
                    IncidentId = incident.Id,
                    DetectedAtUtc = incident.DetectedAtUtc,
                };
                dbContext.Incidents.Add(record);
            }

            record.DeliveryAttemptId = incident.DeliveryAttemptId;
            record.Status = incident.Status.ToString();
            record.Severity = incident.Severity.ToString();
            record.Version = incident.Version;
            record.JsonPayload = JsonSerializer.Serialize(incident, SerializerOptions);
            record.UpdatedAtUtc = incident.UpdatedAtUtc;
        }

        var existingAuditIds = await dbContext.IncidentAudit.AsNoTracking()
            .Where(record => record.ScopeKey == workspace.ScopeKey)
            .Select(record => record.EventId)
            .ToHashSetAsync(cancellationToken);
        dbContext.IncidentAudit.AddRange(workspace.AuditEvents
            .Where(item => !existingAuditIds.Contains(item.Id))
            .Select(item => new IncidentAuditRecordEntity
            {
                ScopeKey = workspace.ScopeKey,
                EventId = item.Id,
                IncidentId = item.IncidentId,
                Action = item.Action,
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
