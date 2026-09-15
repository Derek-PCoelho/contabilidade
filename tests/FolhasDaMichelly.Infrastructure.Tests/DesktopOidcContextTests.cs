using FolhasDaMichelly.Infrastructure.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class DesktopOidcContextTests
{
    [Fact]
    public async Task GuidOpenIddictModelCanInitializeDesktopDatabase()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<OidcClientDbContext>()
            .UseSqlite(connection)
            .Options;

        await using var context = new OidcClientDbContext(options);
        await context.Database.EnsureCreatedAsync();

        Assert.Contains(
            context.Model.GetEntityTypes(),
            entity => entity.ClrType.Name == "OpenIddictEntityFrameworkCoreApplication`1");
        Assert.DoesNotContain(
            context.Model.GetEntityTypes(),
            entity => entity.ClrType.Name == "OpenIddictEntityFrameworkCoreApplication");
    }
}
