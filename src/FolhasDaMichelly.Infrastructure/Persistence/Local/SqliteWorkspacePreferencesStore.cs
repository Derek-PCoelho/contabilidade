using System.Text.Json;
using FolhasDaMichelly.Application.Preferences;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class SqliteWorkspacePreferencesStore(LocalCacheDbContext dbContext)
    : IWorkspacePreferencesStore
{
    private const string PreferencesKey = "desktop.workspace.v1";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<WorkspacePreferences?> LoadAsync(CancellationToken cancellationToken)
    {
        var record = await dbContext.WorkspacePreferences.AsNoTracking()
            .SingleOrDefaultAsync(item => item.Key == PreferencesKey, cancellationToken);
        return record is null
            ? null
            : JsonSerializer.Deserialize<WorkspacePreferences>(record.JsonPayload, SerializerOptions);
    }

    public async Task SaveAsync(
        WorkspacePreferences preferences,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(preferences);
        var record = await dbContext.WorkspacePreferences
            .SingleOrDefaultAsync(item => item.Key == PreferencesKey, cancellationToken);
        if (record is null)
        {
            record = new WorkspacePreferenceRecord { Key = PreferencesKey };
            dbContext.WorkspacePreferences.Add(record);
        }

        record.JsonPayload = JsonSerializer.Serialize(preferences, SerializerOptions);
        record.UpdatedAtUtc = DateTimeOffset.UtcNow;
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
