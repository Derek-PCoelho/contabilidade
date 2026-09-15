using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Common;
using FolhasDaMichelly.Infrastructure.Documents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase4DocumentRecognitionCacheTests
{
    [Fact]
    public async Task CacheNeverPersistsTenantSpecificResolution()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var context = new LocalCacheDbContext(options);
        await context.Database.EnsureCreatedAsync();
        var cache = new SqliteDocumentRecognitionCache(context, new SystemClock());
        var clientId = Guid.NewGuid();
        var result = new DocumentRecognitionResult(
            "synthetic.pdf",
            new string('a', 64),
            "application/pdf",
            100,
            1,
            RecognizedDocumentType.Vacation,
            "test-v1",
            RecognitionConfidence.High,
            .95m,
            false,
            false,
            [],
            new ClientResolutionResult(
                clientId,
                null,
                "Cliente sintético",
                "11.***.***/****-81",
                ClientResolutionMethod.ExactClientTaxId,
                .99m,
                [],
                [],
                []),
            []);

        await cache.PutAsync(result, "test-engine", CancellationToken.None);
        var cached = await cache.GetAsync(result.Sha256, "test-engine", CancellationToken.None);

        Assert.NotNull(cached);
        Assert.Null(cached.Resolution.ClientId);
        Assert.Null(cached.Resolution.ClientDisplayName);
        Assert.Contains(
            "client.resolution_requires_authenticated_refresh",
            cached.Resolution.Blockers);
    }
}
