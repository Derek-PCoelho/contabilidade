using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class SqliteDocumentRecognitionCache(
    LocalCacheDbContext dbContext,
    IClock clock) : IDocumentRecognitionCache
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<DocumentRecognitionResult?> GetAsync(
        string sha256,
        string engineVersion,
        CancellationToken cancellationToken)
    {
        var record = await dbContext.DocumentRecognitions.AsNoTracking().SingleOrDefaultAsync(
            item => item.Sha256 == sha256 && item.EngineVersion == engineVersion,
            cancellationToken);
        return record is null
            ? null
            : JsonSerializer.Deserialize<DocumentRecognitionResult>(record.JsonPayload, SerializerOptions);
    }

    public async Task PutAsync(
        DocumentRecognitionResult result,
        string engineVersion,
        CancellationToken cancellationToken)
    {
        var record = await dbContext.DocumentRecognitions.SingleOrDefaultAsync(
            item => item.Sha256 == result.Sha256,
            cancellationToken);
        if (record is null)
        {
            record = new DocumentRecognitionCacheRecord { Sha256 = result.Sha256 };
            dbContext.DocumentRecognitions.Add(record);
        }

        record.EngineVersion = engineVersion;
        record.JsonPayload = JsonSerializer.Serialize(
            result with
            {
                FromCache = false,
                Resolution = ClientResolutionResult.Unresolved(
                    "client.resolution_requires_authenticated_refresh"),
            },
            SerializerOptions);
        record.RecognizedAtUtc = clock.UtcNow;
        await dbContext.SaveChangesAsync(cancellationToken);
    }
}
