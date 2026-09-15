using System.Text.Json;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Contracts.Clients;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class SqliteClientCatalogCache(LocalCacheDbContext dbContext) : IClientCatalogCache
{
    private const string ClientRecordType = "client";
    private const string TemplateRecordType = "message-template";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<ClientDetails?> GetClientAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var payload = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == ClientRecordType && record.RecordId == clientId)
            .Select(record => record.JsonPayload)
            .SingleOrDefaultAsync(cancellationToken);
        return payload is null
            ? null
            : JsonSerializer.Deserialize<ClientDetails>(payload, SerializerOptions);
    }

    public async Task StoreClientAsync(
        ClientDetails client,
        CancellationToken cancellationToken)
    {
        var record = await dbContext.CatalogRecords.FindAsync(
            [ClientRecordType, client.Id],
            cancellationToken);
        if (record is null)
        {
            record = new CatalogCacheRecord
            {
                RecordType = ClientRecordType,
                RecordId = client.Id,
            };
            dbContext.CatalogRecords.Add(record);
        }

        record.JsonPayload = JsonSerializer.Serialize(client, SerializerOptions);
        record.Version = client.Version;
        record.UpdatedAtUtc = client.UpdatedAtUtc;
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    public async Task StoreTemplatesAsync(
        IReadOnlyList<MessageTemplateModel> templates,
        CancellationToken cancellationToken)
    {
        foreach (var template in templates)
        {
            var record = await dbContext.CatalogRecords.FindAsync(
                [TemplateRecordType, template.Id],
                cancellationToken);
            if (record is null)
            {
                record = new CatalogCacheRecord
                {
                    RecordType = TemplateRecordType,
                    RecordId = template.Id,
                };
                dbContext.CatalogRecords.Add(record);
            }

            record.JsonPayload = JsonSerializer.Serialize(template, SerializerOptions);
            record.Version = template.Version;
            record.UpdatedAtUtc = template.UpdatedAtUtc;
        }

        await dbContext.SaveChangesAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid? clientId,
        CancellationToken cancellationToken)
    {
        var payloads = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == TemplateRecordType)
            .OrderBy(record => record.UpdatedAtUtc)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return payloads
            .Select(payload => JsonSerializer.Deserialize<MessageTemplateModel>(payload, SerializerOptions))
            .Where(template => template is not null &&
                (clientId is null || template.ClientId is null || template.ClientId == clientId))
            .Cast<MessageTemplateModel>()
            .ToArray();
    }
}
