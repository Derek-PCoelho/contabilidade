using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using FolhasDaMichelly.Application.Pilot;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class SqlitePilotChecklistStore(LocalCacheDbContext dbContext) : IPilotChecklistStore
{
    private const string KeyPrefix = "desktop.pilot.v1.";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<PilotChecklistWorkspace> LoadAsync(
        string scopeKey,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(scopeKey);
        var recordKey = BuildKey(scopeKey);
        var record = await dbContext.WorkspacePreferences.AsNoTracking()
            .SingleOrDefaultAsync(item => item.Key == recordKey, cancellationToken);
        if (record is null)
        {
            return PilotChecklistWorkspace.Empty(scopeKey);
        }

        var workspace = JsonSerializer.Deserialize<PilotChecklistWorkspace>(
            record.JsonPayload,
            SerializerOptions);
        return workspace is null || !string.Equals(workspace.ScopeKey, scopeKey, StringComparison.Ordinal)
            ? PilotChecklistWorkspace.Empty(scopeKey)
            : workspace;
    }

    public async Task SaveAsync(
        PilotChecklistWorkspace workspace,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(workspace);
        ArgumentException.ThrowIfNullOrWhiteSpace(workspace.ScopeKey);
        var recordKey = BuildKey(workspace.ScopeKey);
        var record = await dbContext.WorkspacePreferences
            .SingleOrDefaultAsync(item => item.Key == recordKey, cancellationToken);
        if (record is null)
        {
            record = new WorkspacePreferenceRecord { Key = recordKey };
            dbContext.WorkspacePreferences.Add(record);
        }

        record.JsonPayload = JsonSerializer.Serialize(workspace, SerializerOptions);
        record.UpdatedAtUtc = DateTimeOffset.UtcNow;
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    private static string BuildKey(string scopeKey)
    {
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(scopeKey)))
            .ToLowerInvariant();
        return $"{KeyPrefix}{hash[..32]}";
    }
}
