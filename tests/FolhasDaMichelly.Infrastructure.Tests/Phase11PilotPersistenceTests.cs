using FolhasDaMichelly.Application.Pilot;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase11PilotPersistenceTests
{
    [Fact]
    public async Task ChecklistAndAppendOnlyAuditRecoverWithoutCrossingOrganizationScopes()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        var timestamp = new DateTimeOffset(2026, 8, 21, 18, 0, 0, TimeSpan.Zero);
        var workspace = new PilotChecklistWorkspace(
            "organization-a",
            [
                new PilotChecklistItem(
                    PilotChecklistKey.NonProductionDataConfirmed,
                    true,
                    "synthetic-operator",
                    timestamp),
            ],
            [
                new PilotChecklistAuditEvent(
                    Guid.NewGuid(),
                    PilotChecklistKey.NonProductionDataConfirmed,
                    false,
                    true,
                    "synthetic-operator",
                    timestamp),
            ]);

        await using (var context = new LocalCacheDbContext(options))
        {
            await context.Database.EnsureCreatedAsync();
            await new SqlitePilotChecklistStore(context).SaveAsync(workspace, CancellationToken.None);
        }

        await using (var restarted = new LocalCacheDbContext(options))
        {
            var store = new SqlitePilotChecklistStore(restarted);
            var recovered = await store.LoadAsync("organization-a", CancellationToken.None);
            var isolated = await store.LoadAsync("organization-b", CancellationToken.None);

            Assert.True(Assert.Single(recovered.Items).IsConfirmed);
            Assert.Single(recovered.AuditEvents);
            Assert.Empty(isolated.AuditEvents);
            Assert.All(isolated.Items, item => Assert.False(item.IsConfirmed));
        }
    }
}
