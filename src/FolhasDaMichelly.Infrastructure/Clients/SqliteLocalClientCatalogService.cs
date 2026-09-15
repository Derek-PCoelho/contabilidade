using System.Net;
using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Clients;

public sealed class SqliteLocalClientCatalogService(
    LocalCacheDbContext dbContext,
    IClock clock,
    IDocumentReviewService? documentReviewService = null) : IClientCatalogService, ILocalClientCatalogMaintenance
{
    private const int CurrentTransferFormatVersion = 1;
    private const string AuditRecordType = "local-catalog-audit";
    private const string ArchivedClientRecordType = "local-client-archived";
    private const string ArchivedTemplateRecordType = "local-message-template-archived";
    private const string ClientRecordType = "local-client";
    private const string TemplateRecordType = "local-message-template";
    private static readonly Guid LocalActorId = new("9f54a0f0-93a1-4a7c-936d-f88bca95f771");
    private static readonly Guid LocalOrganizationId = new("941a50c9-2e1a-4f22-9f32-70e25e2e481d");
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public async Task<ClientSearchResponse> SearchAsync(
        string? search,
        bool? isActive,
        PersonTypeModel? personType,
        CancellationToken cancellationToken)
    {
        var clients = await LoadClientsAsync(cancellationToken);
        IEnumerable<ClientDetails> query = clients;
        if (isActive is not null)
        {
            query = query.Where(client => client.IsActive == isActive);
        }

        if (personType is not null)
        {
            query = query.Where(client => client.PersonType == personType);
        }

        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim();
            var digits = Digits(term);
            query = query.Where(client =>
                client.LegalNameOrFullName.Contains(term, StringComparison.OrdinalIgnoreCase) ||
                (!string.IsNullOrWhiteSpace(client.PreferredName) &&
                    client.PreferredName.Contains(term, StringComparison.OrdinalIgnoreCase)) ||
                (!string.IsNullOrWhiteSpace(client.InternalCode) &&
                    client.InternalCode.Contains(term, StringComparison.OrdinalIgnoreCase)) ||
                (digits.Length > 0 && client.PrimaryTaxId.Contains(digits, StringComparison.Ordinal)));
        }

        var filtered = query
            .OrderByDescending(client => client.IsActive)
            .ThenBy(client => client.PreferredName ?? client.LegalNameOrFullName, StringComparer.OrdinalIgnoreCase)
            .ToArray();
        const int take = 200;
        return new ClientSearchResponse(
            filtered.Take(take).Select(ToListItem).ToArray(),
            filtered.Length,
            0,
            take);
    }

    public async Task<ClientDetails?> GetAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var payload = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == ClientRecordType && record.RecordId == clientId)
            .Select(record => record.JsonPayload)
            .SingleOrDefaultAsync(cancellationToken);
        return payload is null ? null : DeserializeRequired<ClientDetails>(payload);
    }

    public async Task<ClientDetails> SaveAsync(
        Guid? clientId,
        ClientMutationRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var existing = clientId is null
            ? null
            : await GetAsync(clientId.Value, cancellationToken);
        if (clientId is null && request.ExpectedVersion != 0)
        {
            throw new InvalidOperationException("Um novo cliente deve usar a versão esperada zero.");
        }

        if (clientId is not null && existing is null)
        {
            throw new InvalidOperationException("O cliente que seria alterado não foi encontrado.");
        }

        if (existing is not null && request.ExpectedVersion != existing.Version)
        {
            throw Concurrency(clientId!.Value, request.ExpectedVersion, existing.Version);
        }

        var clients = await LoadClientsAsync(cancellationToken);
        var templates = await LoadTemplatesAsync(cancellationToken);
        ValidateTemplateReferences(request.DefaultSubjectTemplateId, request.DefaultBodyTemplateId, templates);
        if (existing is not null && existing.PersonType != request.PersonType)
        {
            throw new InvalidOperationException(
                "O tipo do cliente não pode ser alterado depois do cadastro. Arquive-o e crie outro cadastro se a identidade estiver incorreta.");
        }

        var now = clock.UtcNow;
        var compatibleExisting = existing is null
            ? null
            : PrepareLegacyEmailDataForMutation(existing, request);
        var normalizedExisting = compatibleExisting is null
            ? null
            : QuarantineMisplacedTaxIdentifiers(compatibleExisting);
        var compatibleRequest = existing is null
            ? request
            : PrepareLegacyEmailRequestForMutation(existing, request);
        var normalizedRequest = existing is null
            ? compatibleRequest
            : QuarantineLegacyMisplacedTaxIdentifiers(existing, compatibleRequest);
        var normalized = ValidateDomain(() => NormalizeClient(normalizedExisting, normalizedRequest, now));
        var saved = Map(normalized) with
        {
            Version = existing?.Version + 1 ?? 1,
            CreatedAtUtc = existing?.CreatedAtUtc ?? now,
            UpdatedAtUtc = now,
        };
        ValidateClientUniqueness(saved, clients.Where(client => client.Id != saved.Id));

        return await PersistClientChangeWithReviewAsync(
            async ct =>
            {
                await UpsertAsync(ClientRecordType, saved.Id, saved.Version, saved.UpdatedAtUtc, saved, ct);
                AddAudit(
                    "client",
                    saved.Id,
                    ClientAuditAction(existing, saved),
                    saved.Version);
                await dbContext.SaveChangesAsync(ct);
                return saved;
            },
            cancellationToken);
    }

    public async Task<ClientDetails> SetClientActiveAsync(
        Guid clientId,
        long expectedVersion,
        bool isActive,
        CancellationToken cancellationToken)
    {
        var existing = await RequireClientAsync(clientId, cancellationToken);
        EnsureExpectedVersion(clientId, expectedVersion, existing.Version);
        if (existing.IsActive == isActive)
        {
            return existing;
        }

        var now = clock.UtcNow;
        var updated = QuarantineMisplacedTaxIdentifiers(existing) with
        {
            IsActive = isActive,
            Version = existing.Version + 1,
            UpdatedAtUtc = now,
        };
        if (isActive)
        {
            // Alterar o status não regrava os dados do cadastro. Registros legados podem
            // conter um e-mail que a validação atual já não aceita; eles devem reabrir para
            // correção, permanecendo bloqueados pela prontidão até o usuário salvá-los.
            ValidateClientUniqueness(
                updated,
                (await LoadClientsAsync(cancellationToken)).Where(client => client.Id != updated.Id));
        }

        return await PersistClientChangeWithReviewAsync(
            async ct =>
            {
                await UpsertAsync(
                    ClientRecordType,
                    updated.Id,
                    updated.Version,
                    updated.UpdatedAtUtc,
                    updated,
                    ct);
                AddAudit("client", updated.Id, isActive ? "reactivated" : "deactivated", updated.Version);
                await dbContext.SaveChangesAsync(ct);
                return updated;
            },
            cancellationToken);
    }

    private async Task<T> PersistClientChangeWithReviewAsync<T>(
        Func<CancellationToken, Task<T>> persistChange,
        CancellationToken cancellationToken)
    {
        if (documentReviewService is null)
        {
            return await persistChange(cancellationToken);
        }

        var ownsTransaction = dbContext.Database.CurrentTransaction is null;
        await using var transaction = ownsTransaction
            ? await dbContext.Database.BeginTransactionAsync(cancellationToken)
            : null;
        try
        {
            var result = await persistChange(cancellationToken);

            // Qualquer alteração cadastral pode mudar associação, elegibilidade ou o
            // conteúdo de uma mensagem. Cadastro e invalidação das aprovações precisam
            // confirmar juntos; uma falha de revisão reverte todo o conjunto.
            _ = await documentReviewService.RevalidateAsync(cancellationToken);

            if (transaction is not null)
            {
                await transaction.CommitAsync(cancellationToken);
                dbContext.ChangeTracker.Clear();
            }

            return result;
        }
        catch
        {
            if (transaction is not null)
            {
                await transaction.RollbackAsync(CancellationToken.None);

                // SaveChanges pode ter marcado entidades como persistidas antes de o
                // SQLite receber o rollback. Limpar o rastreamento impede que uma leitura
                // seguinte exponha o estado corretamente desfeito no banco.
                dbContext.ChangeTracker.Clear();
            }

            throw;
        }
    }

    public async Task<LocalCatalogArchiveResult> ArchiveClientAsync(
        Guid clientId,
        long expectedVersion,
        CancellationToken cancellationToken)
    {
        var existing = await RequireClientAsync(clientId, cancellationToken);
        EnsureExpectedVersion(clientId, expectedVersion, existing.Version);
        if (existing.IsActive)
        {
            throw new InvalidOperationException(
                "Inative o cliente antes de excluí-lo da lista. Assim nenhuma operação em andamento perde a referência.");
        }

        await EnsureClientHasNoOperationalReferencesAsync(clientId, cancellationToken);
        var archivedAt = clock.UtcNow;
        var archived = existing with
        {
            Version = existing.Version + 1,
            UpdatedAtUtc = archivedAt,
        };
        await MoveToArchiveAsync(
            ClientRecordType,
            ArchivedClientRecordType,
            archived.Id,
            archived.Version,
            archivedAt,
            archived,
            cancellationToken);
        AddAudit("client", archived.Id, "archived", archived.Version);
        await dbContext.SaveChangesAsync(cancellationToken);
        return new LocalCatalogArchiveResult(archived.Id, archived.Version, archivedAt);
    }

    public async Task<ClientReadinessResponse?> GetReadinessAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var stored = await GetAsync(clientId, cancellationToken);
        if (stored is null)
        {
            return null;
        }

        var today = DateOnly.FromDateTime(clock.UtcNow.UtcDateTime);
        var legacyBlocks = GetLegacyEmailReadinessBlocks(stored, today);
        if (legacyBlocks.Length > 0)
        {
            return new ClientReadinessResponse(stored.Id, false, legacyBlocks);
        }

        var readinessCompatible = RemoveInactiveLegacyInvalidEmails(stored);
        var client = ValidateDomain(() => new Client(
            stored.Id,
            LocalOrganizationId,
            ToDomain(readinessCompatible),
            LocalActorId,
            stored.UpdatedAtUtc));
        var readiness = ClientOperationalReadinessEvaluator.Evaluate(client, today);
        return new ClientReadinessResponse(client.Id, readiness.IsEligible, readiness.BlockCodes);
    }

    public async Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid? clientId,
        bool includeInactive,
        CancellationToken cancellationToken)
    {
        var templates = await LoadTemplatesAsync(cancellationToken);
        return templates
            .Where(template =>
                (clientId is null || template.ClientId is null || template.ClientId == clientId) &&
                (includeInactive || template.IsActive))
            .OrderBy(template => template.Name, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    public async Task<MessageTemplateModel> SaveTemplateAsync(
        Guid? templateId,
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var templates = await LoadTemplatesAsync(cancellationToken);
        var existing = templateId is null
            ? null
            : templates.SingleOrDefault(template => template.Id == templateId);
        if (templateId is null && request.ExpectedVersion != 0)
        {
            throw new InvalidOperationException("Um novo modelo deve usar a versão esperada zero.");
        }

        if (templateId is not null && existing is null)
        {
            throw new InvalidOperationException("O modelo que seria alterado não foi encontrado.");
        }

        if (existing is not null && request.ExpectedVersion != existing.Version)
        {
            throw Concurrency(templateId!.Value, request.ExpectedVersion, existing.Version);
        }

        if (request.ClientId is { } requestedClientId &&
            await GetAsync(requestedClientId, cancellationToken) is null)
        {
            throw new InvalidOperationException("O cliente associado ao modelo não foi encontrado.");
        }

        if (!request.IsActive)
        {
            await EnsureTemplateIsNotReferencedAsDefaultAsync(
                templateId,
                cancellationToken);
        }

        ValidateTemplateUniqueness(templateId, request, templates);
        var now = clock.UtcNow;
        var domain = ValidateDomain(() => new MessageTemplate(
            templateId ?? Guid.NewGuid(),
            LocalOrganizationId,
            ToDomain(request),
            LocalActorId,
            now));
        var saved = Map(domain) with
        {
            Version = existing?.Version + 1 ?? 1,
            UpdatedAtUtc = now,
        };
        await UpsertAsync(TemplateRecordType, saved.Id, saved.Version, saved.UpdatedAtUtc, saved, cancellationToken);
        AddAudit("message-template", saved.Id, existing is null ? "created" : "updated", saved.Version);
        await dbContext.SaveChangesAsync(cancellationToken);
        return saved;
    }

    public async Task<MessageTemplateModel> SetTemplateActiveAsync(
        Guid templateId,
        long expectedVersion,
        bool isActive,
        CancellationToken cancellationToken)
    {
        var templates = await LoadTemplatesAsync(cancellationToken);
        var existing = templates.SingleOrDefault(template => template.Id == templateId)
            ?? throw new InvalidOperationException("O modelo de mensagem não foi encontrado.");
        EnsureExpectedVersion(templateId, expectedVersion, existing.Version);
        if (existing.IsActive == isActive)
        {
            return existing;
        }

        if (!isActive)
        {
            await EnsureTemplateIsNotReferencedAsDefaultAsync(templateId, cancellationToken);
        }

        var request = ToMutation(existing, isActive);
        ValidateTemplateUniqueness(templateId, request, templates);
        var now = clock.UtcNow;
        var domain = ValidateDomain(() => new MessageTemplate(
            existing.Id,
            LocalOrganizationId,
            ToDomain(request),
            LocalActorId,
            now));
        var updated = Map(domain) with
        {
            Version = existing.Version + 1,
            UpdatedAtUtc = now,
        };
        await UpsertAsync(
            TemplateRecordType,
            updated.Id,
            updated.Version,
            updated.UpdatedAtUtc,
            updated,
            cancellationToken);
        AddAudit(
            "message-template",
            updated.Id,
            isActive ? "reactivated" : "deactivated",
            updated.Version);
        await dbContext.SaveChangesAsync(cancellationToken);
        return updated;
    }

    public async Task<LocalCatalogArchiveResult> ArchiveTemplateAsync(
        Guid templateId,
        long expectedVersion,
        CancellationToken cancellationToken)
    {
        var existing = (await LoadTemplatesAsync(cancellationToken))
            .SingleOrDefault(template => template.Id == templateId)
            ?? throw new InvalidOperationException("O modelo de mensagem não foi encontrado.");
        EnsureExpectedVersion(templateId, expectedVersion, existing.Version);
        if (existing.IsActive)
        {
            throw new InvalidOperationException(
                "Inative a mensagem antes de excluí-la da lista.");
        }

        await EnsureTemplateIsNotReferencedAsDefaultAsync(templateId, cancellationToken);
        var archivedAt = clock.UtcNow;
        var archived = existing with
        {
            Version = existing.Version + 1,
            UpdatedAtUtc = archivedAt,
        };
        await MoveToArchiveAsync(
            TemplateRecordType,
            ArchivedTemplateRecordType,
            archived.Id,
            archived.Version,
            archivedAt,
            archived,
            cancellationToken);
        AddAudit("message-template", archived.Id, "archived", archived.Version);
        await dbContext.SaveChangesAsync(cancellationToken);
        return new LocalCatalogArchiveResult(archived.Id, archived.Version, archivedAt);
    }

    public async Task<ClientCatalogTransferDocument> ExportAsync(
        CancellationToken cancellationToken) => new(
        CurrentTransferFormatVersion,
        clock.UtcNow,
        (await LoadClientsAsync(cancellationToken))
            .OrderBy(client => client.LegalNameOrFullName, StringComparer.OrdinalIgnoreCase)
            .ToArray(),
        (await LoadTemplatesAsync(cancellationToken))
            .OrderBy(template => template.Name, StringComparer.OrdinalIgnoreCase)
            .ToArray());

    public async Task<ClientCatalogImportResult> ImportAsync(
        ClientCatalogImportRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.Document.FormatVersion != CurrentTransferFormatVersion)
        {
            throw new InvalidOperationException(
                $"A versão {request.Document.FormatVersion} da cópia de segurança não é compatível.");
        }

        if (request.Document.Clients.Select(client => client.Id).Distinct().Count() != request.Document.Clients.Count ||
            request.Document.Templates.Select(template => template.Id).Distinct().Count() != request.Document.Templates.Count)
        {
            throw new InvalidOperationException("A cópia de segurança contém identificadores repetidos.");
        }

        var existingClients = (await LoadClientsAsync(cancellationToken)).ToDictionary(client => client.Id);
        var existingTemplates = (await LoadTemplatesAsync(cancellationToken)).ToDictionary(template => template.Id);
        var workingClients = new Dictionary<Guid, ClientDetails>(existingClients);
        var workingTemplates = new Dictionary<Guid, MessageTemplateModel>(existingTemplates);
        var changedClients = new List<ClientDetails>();
        var changedTemplates = new List<MessageTemplateModel>();
        var warnings = new List<string>();
        var clientsCreated = 0;
        var clientsUpdated = 0;
        var templatesCreated = 0;
        var templatesUpdated = 0;
        var now = clock.UtcNow;

        foreach (var imported in request.Document.Clients)
        {
            existingClients.TryGetValue(imported.Id, out var existing);
            if (existing is not null && !request.OverwriteExisting)
            {
                warnings.Add($"O cliente {imported.Id:D} já existe e foi ignorado.");
                continue;
            }

            var domain = ValidateDomain(() => new Client(
                imported.Id,
                LocalOrganizationId,
                ToDomain(imported),
                LocalActorId,
                now));
            var normalized = Map(domain) with
            {
                Version = existing?.Version + 1 ?? 1,
                CreatedAtUtc = existing?.CreatedAtUtc ?? now,
                UpdatedAtUtc = now,
            };
            workingClients[normalized.Id] = normalized;
            changedClients.Add(normalized);
            if (existing is null)
            {
                clientsCreated++;
            }
            else
            {
                clientsUpdated++;
            }
        }

        foreach (var imported in request.Document.Templates)
        {
            existingTemplates.TryGetValue(imported.Id, out var existing);
            if (existing is not null && !request.OverwriteExisting)
            {
                warnings.Add($"O modelo {imported.Id:D} já existe e foi ignorado.");
                continue;
            }

            var domain = ValidateDomain(() => new MessageTemplate(
                imported.Id,
                LocalOrganizationId,
                ToDomain(imported),
                LocalActorId,
                now));
            var normalized = Map(domain) with
            {
                Version = existing?.Version + 1 ?? 1,
                UpdatedAtUtc = now,
            };
            workingTemplates[normalized.Id] = normalized;
            changedTemplates.Add(normalized);
            if (existing is null)
            {
                templatesCreated++;
            }
            else
            {
                templatesUpdated++;
            }
        }

        ValidateImportedCatalog(workingClients.Values, workingTemplates.Values);
        if (!request.DryRun)
        {
            foreach (var client in changedClients)
            {
                await UpsertAsync(
                    ClientRecordType,
                    client.Id,
                    client.Version,
                    client.UpdatedAtUtc,
                    client,
                    cancellationToken);
                AddAudit("client", client.Id, "imported", client.Version);
            }

            foreach (var template in changedTemplates)
            {
                await UpsertAsync(
                    TemplateRecordType,
                    template.Id,
                    template.Version,
                    template.UpdatedAtUtc,
                    template,
                    cancellationToken);
                AddAudit("message-template", template.Id, "imported", template.Version);
            }

            await dbContext.SaveChangesAsync(cancellationToken);
        }

        return new ClientCatalogImportResult(
            request.DryRun,
            clientsCreated,
            clientsUpdated,
            templatesCreated,
            templatesUpdated,
            warnings);
    }

    public async Task<IReadOnlyList<AuditEventModel>> GetAuditAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var payloads = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == AuditRecordType)
            .OrderByDescending(record => record.UpdatedAtUtc)
            .Take(1000)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return payloads
            .Select(DeserializeRequired<AuditEventModel>)
            .Where(item => item.EntityType == "client" && item.EntityId == clientId.ToString("D"))
            .OrderByDescending(item => item.TimestampUtc)
            .Take(100)
            .ToArray();
    }

    private async Task<IReadOnlyList<ClientDetails>> LoadClientsAsync(
        CancellationToken cancellationToken)
    {
        var payloads = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == ClientRecordType)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return payloads.Select(DeserializeRequired<ClientDetails>).ToArray();
    }

    private async Task<ClientDetails> RequireClientAsync(
        Guid clientId,
        CancellationToken cancellationToken) =>
        await GetAsync(clientId, cancellationToken)
        ?? throw new InvalidOperationException("O cliente não foi encontrado.");

    private async Task<IReadOnlyList<MessageTemplateModel>> LoadTemplatesAsync(
        CancellationToken cancellationToken)
    {
        var payloads = await dbContext.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == TemplateRecordType)
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        return payloads.Select(DeserializeRequired<MessageTemplateModel>).ToArray();
    }

    private async Task UpsertAsync<T>(
        string recordType,
        Guid recordId,
        long version,
        DateTimeOffset updatedAtUtc,
        T value,
        CancellationToken cancellationToken)
    {
        var record = await dbContext.CatalogRecords.FindAsync(
            [recordType, recordId],
            cancellationToken);
        if (record is null)
        {
            record = new CatalogCacheRecord
            {
                RecordType = recordType,
                RecordId = recordId,
            };
            dbContext.CatalogRecords.Add(record);
        }

        record.JsonPayload = JsonSerializer.Serialize(value, SerializerOptions);
        record.Version = version;
        record.UpdatedAtUtc = updatedAtUtc;
    }

    private async Task MoveToArchiveAsync<T>(
        string currentRecordType,
        string archivedRecordType,
        Guid recordId,
        long version,
        DateTimeOffset archivedAtUtc,
        T value,
        CancellationToken cancellationToken)
    {
        var current = await dbContext.CatalogRecords.FindAsync(
            [currentRecordType, recordId],
            cancellationToken)
            ?? throw new InvalidOperationException("O registro que seria arquivado não foi encontrado.");
        if (await dbContext.CatalogRecords.AsNoTracking().AnyAsync(record =>
                record.RecordType == archivedRecordType && record.RecordId == recordId,
                cancellationToken))
        {
            throw new InvalidOperationException(
                "Já existe uma cópia arquivada deste registro. Nenhum dado foi alterado.");
        }

        dbContext.CatalogRecords.Remove(current);
        dbContext.CatalogRecords.Add(new CatalogCacheRecord
        {
            RecordType = archivedRecordType,
            RecordId = recordId,
            JsonPayload = JsonSerializer.Serialize(value, SerializerOptions),
            Version = version,
            UpdatedAtUtc = archivedAtUtc,
        });
    }

    private async Task EnsureClientHasNoOperationalReferencesAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        if ((await LoadTemplatesAsync(cancellationToken)).Any(template => template.ClientId == clientId))
        {
            throw new InvalidOperationException(
                "Este cliente possui mensagens personalizadas. Exclua primeiro essas mensagens inativas.");
        }

        if (await dbContext.DocumentReviewGroups.AsNoTracking()
            .AnyAsync(group => group.ClientId == clientId, cancellationToken))
        {
            throw new InvalidOperationException(
                "Este cliente aparece em uma revisão de documentos. Retire os documentos relacionados antes de excluir o cadastro.");
        }

        var reviewPayloads = await dbContext.DocumentReviews.AsNoTracking()
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        if (reviewPayloads.Select(DeserializeReviewDocument).Any(document => document.ClientId == clientId))
        {
            throw new InvalidOperationException(
                "Este cliente aparece em um documento importado. Retire o documento relacionado antes de excluir o cadastro.");
        }

        var dispatchPayloads = await dbContext.DispatchItems.AsNoTracking()
            .Select(record => record.JsonPayload)
            .ToArrayAsync(cancellationToken);
        if (dispatchPayloads.Select(DeserializeDispatchItem).Any(item => item.ClientId == clientId))
        {
            throw new InvalidOperationException(
                "Este cliente aparece em uma mensagem preparada ou concluída e precisa permanecer no histórico.");
        }
    }

    private async Task EnsureTemplateIsNotReferencedAsDefaultAsync(
        Guid? templateId,
        CancellationToken cancellationToken)
    {
        if (templateId is null)
        {
            return;
        }

        var referenced = (await LoadClientsAsync(cancellationToken)).Any(client =>
            client.DefaultSubjectTemplateId == templateId ||
            client.DefaultBodyTemplateId == templateId);
        if (referenced)
        {
            throw new InvalidOperationException(
                "Esta mensagem está definida como padrão em um cliente. Escolha outro padrão antes de inativar ou excluir.");
        }
    }

    private void AddAudit(string entityType, Guid entityId, string action, long version)
    {
        var timestamp = clock.UtcNow;
        var audit = new AuditEventModel(
            Guid.NewGuid(),
            entityType,
            entityId.ToString("D"),
            action,
            "registration",
            "information",
            JsonSerializer.Serialize(new { version, storage = "local" }, SerializerOptions),
            timestamp,
            Guid.NewGuid());
        dbContext.CatalogRecords.Add(new CatalogCacheRecord
        {
            RecordType = AuditRecordType,
            RecordId = audit.Id,
            JsonPayload = JsonSerializer.Serialize(audit, SerializerOptions),
            Version = 1,
            UpdatedAtUtc = timestamp,
        });
    }

    private static void ValidateImportedCatalog(
        IEnumerable<ClientDetails> clients,
        IEnumerable<MessageTemplateModel> templates)
    {
        var clientItems = clients.ToArray();
        var templateItems = templates.ToArray();
        for (var index = 0; index < clientItems.Length; index++)
        {
            ValidateClientUniqueness(clientItems[index], clientItems[(index + 1)..]);
        }

        var clientIds = clientItems.Select(client => client.Id).ToHashSet();
        if (templateItems.Any(template => template.ClientId is { } clientId && !clientIds.Contains(clientId)))
        {
            throw new InvalidOperationException("Todo modelo importado deve referenciar um cliente existente.");
        }

        var activeTemplateIds = templateItems.Where(template => template.IsActive)
            .Select(template => template.Id)
            .ToHashSet();
        foreach (var client in clientItems)
        {
            ValidateTemplateReferences(
                client.DefaultSubjectTemplateId,
                client.DefaultBodyTemplateId,
                templateItems,
                activeTemplateIds);
        }

        var duplicateNames = templateItems.GroupBy(
                template => (template.ClientId, Name: template.Name.Trim()),
                TemplateNameKeyComparer.Instance)
            .Any(group => group.Count() > 1);
        if (duplicateNames)
        {
            throw new InvalidOperationException("A cópia criaria modelos com nomes repetidos no mesmo cadastro.");
        }

        var duplicateDefaults = templateItems
            .Where(template => template.IsActive && template.IsDefault)
            .GroupBy(template => (template.ClientId, template.DocumentTypeId))
            .Any(group => group.Count() > 1);
        if (duplicateDefaults)
        {
            throw new InvalidOperationException(
                "Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
        }
    }

    private static void ValidateClientUniqueness(
        ClientDetails candidate,
        IEnumerable<ClientDetails> otherClients)
    {
        var others = otherClients.ToArray();
        var candidateTaxIdentities = TaxIdentities(candidate);
        var otherTaxIdentities = others.SelectMany(TaxIdentities).ToHashSet();
        if (candidateTaxIdentities.Any(otherTaxIdentities.Contains))
        {
            throw new InvalidOperationException(
                "Este CPF, CNPJ, raiz empresarial ou estabelecimento já pertence a outro cliente.");
        }

        var candidateCodes = InternalCodes(candidate);
        var otherCodes = others.SelectMany(InternalCodes).ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (candidateCodes.Any(otherCodes.Contains))
        {
            throw new InvalidOperationException("Este código contábil já pertence a outro cliente.");
        }

        var candidateIdentifiers = ComparableIdentifiers(candidate)
            .Where(identifier => identifier.IsUniqueWithinOrganization)
            .Select(identifier => (identifier.Type, identifier.Value))
            .ToHashSet();
        var uniqueOtherIdentifiers = others.SelectMany(ComparableIdentifiers)
            .Where(identifier => identifier.IsUniqueWithinOrganization)
            .Select(identifier => (identifier.Type, identifier.Value))
            .ToHashSet();
        var allOtherIdentifiers = others.SelectMany(ComparableIdentifiers)
            .Select(identifier => (identifier.Type, identifier.Value))
            .ToHashSet();
        var allCandidateIdentifiers = ComparableIdentifiers(candidate)
            .Select(identifier => (identifier.Type, identifier.Value))
            .ToHashSet();
        if (candidateIdentifiers.Any(allOtherIdentifiers.Contains) ||
            uniqueOtherIdentifiers.Any(allCandidateIdentifiers.Contains))
        {
            throw new InvalidOperationException("Um identificador informado já pertence a outro cliente.");
        }
    }

    private static HashSet<(string Kind, string Value)> TaxIdentities(ClientDetails client)
    {
        var values = new HashSet<(string Kind, string Value)>();
        if (client.PersonType == PersonTypeModel.Individual)
        {
            values.Add(("cpf", client.PrimaryTaxId));
        }
        else
        {
            values.Add(("cnpj", client.PrimaryTaxId));
            values.Add(("cnpj-root", client.PrimaryTaxId[..8]));
        }

        foreach (var identifier in client.Identifiers)
        {
            switch (identifier.Type)
            {
                case ClientIdentifierTypeModel.Cpf when client.PersonType == PersonTypeModel.Individual:
                    values.Add(("cpf", identifier.Value));
                    break;
                case ClientIdentifierTypeModel.Cnpj when client.PersonType == PersonTypeModel.LegalEntity:
                    values.Add(("cnpj", identifier.Value));
                    values.Add(("cnpj-root", identifier.Value[..8]));
                    break;
                case ClientIdentifierTypeModel.CnpjRoot when client.PersonType == PersonTypeModel.LegalEntity:
                    values.Add(("cnpj-root", identifier.Value));
                    break;
            }
        }

        foreach (var establishment in client.Establishments)
        {
            values.Add(("cnpj", establishment.Cnpj));
            values.Add(("cnpj-root", establishment.Cnpj[..8]));
        }

        return values;
    }

    private static IEnumerable<ClientIdentifierModel> ComparableIdentifiers(ClientDetails client) =>
        client.Identifiers.Where(identifier =>
            !IsMisplacedTaxIdentifier(client.PersonType, identifier.Type));

    private static HashSet<string> InternalCodes(ClientDetails client)
    {
        var values = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (!string.IsNullOrWhiteSpace(client.InternalCode))
        {
            values.Add(client.InternalCode);
        }

        foreach (var identifier in client.Identifiers.Where(identifier =>
                     identifier.Type == ClientIdentifierTypeModel.InternalCode))
        {
            values.Add(identifier.Value);
        }

        foreach (var establishment in client.Establishments.Where(establishment =>
                     !string.IsNullOrWhiteSpace(establishment.InternalCode)))
        {
            values.Add(establishment.InternalCode!);
        }

        return values;
    }

    private static ClientDetails QuarantineMisplacedTaxIdentifiers(ClientDetails client) => client with
    {
        Identifiers = QuarantineMisplacedTaxIdentifiers(client.PersonType, client.Identifiers),
    };

    private static ClientDetails PrepareLegacyEmailDataForMutation(
        ClientDetails existing,
        ClientMutationRequest request) => existing with
        {
            Recipients = PrepareLegacyRecipientsForMutation(existing.Recipients, request.Recipients),
            Partners = PrepareLegacyPartnersForMutation(existing.Partners ?? [], request.Partners ?? []),
        };

    private static ClientDetails RemoveInactiveLegacyInvalidEmails(ClientDetails client) => client with
    {
        Recipients = client.Recipients
            .Where(recipient =>
                recipient.IsActive || EmailAddress.TryNormalize(recipient.Email, out _))
            .ToArray(),
        Partners = (client.Partners ?? [])
            .Where(partner => partner.IsActive || IsValidOptionalEmail(partner.Email))
            .ToArray(),
    };

    private static ClientMutationRequest PrepareLegacyEmailRequestForMutation(
        ClientDetails existing,
        ClientMutationRequest request)
    {
        var invalidRecipientIds = existing.Recipients
            .Where(recipient => !EmailAddress.TryNormalize(recipient.Email, out _))
            .Select(recipient => recipient.Id)
            .ToHashSet();
        var invalidPartnerIds = (existing.Partners ?? [])
            .Where(partner => !IsValidOptionalEmail(partner.Email))
            .Select(partner => partner.Id)
            .ToHashSet();
        return request with
        {
            Recipients = request.Recipients
                .Where(recipient =>
                    !invalidRecipientIds.Contains(recipient.Id) ||
                    recipient.IsActive ||
                    EmailAddress.TryNormalize(recipient.Email, out _))
                .ToArray(),
            Partners = (request.Partners ?? [])
                .Where(partner =>
                    !invalidPartnerIds.Contains(partner.Id) ||
                    partner.IsActive ||
                    IsValidOptionalEmail(partner.Email))
                .ToArray(),
        };
    }

    private static RecipientModel[] PrepareLegacyRecipientsForMutation(
        IReadOnlyList<RecipientModel> existing,
        IReadOnlyList<RecipientModel> requested) => existing
        .Where(recipient =>
            EmailAddress.TryNormalize(recipient.Email, out _) ||
            requested.Any(candidate =>
                candidate.Id == recipient.Id &&
                (candidate.IsActive || EmailAddress.TryNormalize(candidate.Email, out _))))
        .Select(recipient =>
        {
            if (EmailAddress.TryNormalize(recipient.Email, out _))
            {
                return recipient;
            }

            var replacement = requested.First(candidate => candidate.Id == recipient.Id);
            return EmailAddress.TryNormalize(replacement.Email, out _) ? replacement : recipient;
        })
        .ToArray();

    private static ClientPartnerModel[] PrepareLegacyPartnersForMutation(
        IReadOnlyList<ClientPartnerModel> existing,
        IReadOnlyList<ClientPartnerModel> requested) => existing
        .Where(partner =>
            IsValidOptionalEmail(partner.Email) ||
            requested.Any(candidate =>
                candidate.Id == partner.Id &&
                (candidate.IsActive || IsValidOptionalEmail(candidate.Email))))
        .Select(partner =>
        {
            if (IsValidOptionalEmail(partner.Email))
            {
                return partner;
            }

            var replacement = requested.First(candidate => candidate.Id == partner.Id);
            return IsValidOptionalEmail(replacement.Email) ? replacement : partner;
        })
        .ToArray();

    private static string[] GetLegacyEmailReadinessBlocks(ClientDetails client, DateOnly today)
    {
        var blocks = new List<string>();
        if (!client.IsActive)
        {
            blocks.Add(ClientBlockCodes.ClientInactive);
        }

        var hasInvalidRecipient = client.Recipients.Any(recipient =>
            recipient.IsActive &&
            !EmailAddress.TryNormalize(recipient.Email, out _));
        if (hasInvalidRecipient)
        {
            blocks.Add(ClientBlockCodes.RecipientEmailInvalid);
        }

        if (!client.Recipients.Any(recipient =>
                recipient.DeliveryRole == DeliveryRoleModel.To &&
                recipient.IsActive &&
                (recipient.ValidFrom is null || recipient.ValidFrom <= today) &&
                (recipient.ValidTo is null || recipient.ValidTo >= today) &&
                EmailAddress.TryNormalize(recipient.Email, out _)))
        {
            blocks.Add(ClientBlockCodes.NoActiveToRecipient);
        }

        if ((client.Partners ?? []).Any(partner =>
                partner.IsActive && !IsValidOptionalEmail(partner.Email)))
        {
            blocks.Add(ClientBlockCodes.PartnerEmailInvalid);
        }

        return blocks.Distinct(StringComparer.Ordinal).ToArray();
    }

    private static bool IsValidOptionalEmail(string? email) =>
        string.IsNullOrWhiteSpace(email) || EmailAddress.TryNormalize(email, out _);

    private static ClientMutationRequest QuarantineLegacyMisplacedTaxIdentifiers(
        ClientDetails existing,
        ClientMutationRequest request)
    {
        var legacyIdentifierIds = existing.Identifiers
            .Where(identifier => IsMisplacedTaxIdentifier(existing.PersonType, identifier.Type))
            .Select(identifier => identifier.Id)
            .ToHashSet();
        return request with
        {
            Identifiers = request.Identifiers
                .Select(identifier =>
                    legacyIdentifierIds.Contains(identifier.Id) &&
                    IsMisplacedTaxIdentifier(request.PersonType, identifier.Type) &&
                    identifier.IsActive
                        ? identifier with { IsActive = false }
                        : identifier)
                .ToArray(),
        };
    }

    private static ClientIdentifierModel[] QuarantineMisplacedTaxIdentifiers(
        PersonTypeModel personType,
        IReadOnlyList<ClientIdentifierModel> identifiers) => identifiers
        .Select(identifier => IsMisplacedTaxIdentifier(personType, identifier.Type) && identifier.IsActive
            ? identifier with { IsActive = false }
            : identifier)
        .ToArray();

    private static bool IsMisplacedTaxIdentifier(
        PersonTypeModel personType,
        ClientIdentifierTypeModel identifierType) => personType switch
        {
            PersonTypeModel.LegalEntity => identifierType == ClientIdentifierTypeModel.Cpf,
            PersonTypeModel.Individual => identifierType is
                ClientIdentifierTypeModel.Cnpj or ClientIdentifierTypeModel.CnpjRoot,
            _ => false,
        };

    private static void ValidateTemplateReferences(
        Guid? subjectTemplateId,
        Guid? bodyTemplateId,
        IReadOnlyCollection<MessageTemplateModel> templates,
        IReadOnlySet<Guid>? activeTemplateIds = null)
    {
        var requestedIds = new[] { subjectTemplateId, bodyTemplateId }
            .Where(id => id is not null && id != Guid.Empty)
            .Select(id => id!.Value)
            .Distinct()
            .ToArray();
        if (requestedIds.Length == 0)
        {
            return;
        }

        var activeIds = activeTemplateIds ?? templates.Where(template => template.IsActive)
            .Select(template => template.Id)
            .ToHashSet();
        if (requestedIds.Any(id => !activeIds.Contains(id)))
        {
            throw new InvalidOperationException("Os modelos padrão devem existir e estar ativos.");
        }
    }

    private static void ValidateTemplateUniqueness(
        Guid? templateId,
        MessageTemplateMutationRequest request,
        IEnumerable<MessageTemplateModel> templates)
    {
        var others = templates.Where(template => template.Id != templateId).ToArray();
        if (others.Any(template =>
                template.ClientId == request.ClientId &&
                string.Equals(template.Name, request.Name.Trim(), StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("Já existe um modelo com este nome para o mesmo cadastro.");
        }

        if (request.IsActive && request.IsDefault && others.Any(template =>
                template.IsActive &&
                template.IsDefault &&
                template.ClientId == request.ClientId &&
                template.DocumentTypeId == request.DocumentTypeId))
        {
            throw new InvalidOperationException(
                "Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
        }
    }

    private static ClientListItem ToListItem(ClientDetails client) => new(
        client.Id,
        client.PersonType,
        client.PreferredName ?? client.LegalNameOrFullName,
        BrazilianRegistration.Mask(client.PrimaryTaxId),
        client.InternalCode,
        client.IsActive,
        client.Version,
        client.Establishments.Count(item => item.IsActive),
        client.Recipients.Count(item => item.IsActive),
        client.UpdatedAtUtc);

    private static Client NormalizeClient(
        ClientDetails? existing,
        ClientMutationRequest request,
        DateTimeOffset now)
    {
        if (existing is null)
        {
            return new Client(
                Guid.NewGuid(),
                LocalOrganizationId,
                ToDomain(request),
                LocalActorId,
                now);
        }

        var client = new Client(
            existing.Id,
            LocalOrganizationId,
            ToDomain(existing),
            LocalActorId,
            existing.CreatedAtUtc);
        client.Apply(ToDomain(request), client.Version, LocalActorId, now);
        return client;
    }

    private static string ClientAuditAction(ClientDetails? existing, ClientDetails saved) => existing switch
    {
        null => "created",
        { IsActive: true } when !saved.IsActive => "deactivated",
        { IsActive: false } when saved.IsActive => "reactivated",
        _ => "updated",
    };

    private static ClientDetails Map(Client client) => new(
        client.Id,
        (PersonTypeModel)client.PersonType,
        client.LegalNameOrFullName,
        client.PreferredName,
        client.InternalCode,
        client.PrimaryTaxIdNormalized,
        client.IsActive,
        client.DefaultSubjectTemplateId,
        client.DefaultBodyTemplateId,
        client.Notes,
        client.Version,
        client.CreatedAtUtc,
        client.UpdatedAtUtc,
        client.Identifiers.OrderBy(item => item.Priority).Select(item => new ClientIdentifierModel(
            item.Id,
            (ClientIdentifierTypeModel)item.Type,
            item.ValueNormalized,
            (ClientIdentifierSemanticRoleModel)item.SemanticRole,
            item.Priority,
            item.IsActive,
            item.IsUniqueWithinOrganization)).ToArray(),
        client.Establishments.OrderByDescending(item => item.IsHeadOffice).ThenBy(item => item.DisplayName)
            .Select(item => new EstablishmentModel(
                item.Id,
                item.CnpjNormalized,
                item.LegalName,
                item.DisplayName,
                item.InternalCode,
                item.IsHeadOffice,
                item.IsActive)).ToArray(),
        client.Recipients.OrderBy(item => item.DeliveryRole).ThenBy(item => item.DisplayName)
            .Select(item => new RecipientModel(
                item.Id,
                item.EstablishmentId,
                item.DisplayName,
                item.EmailNormalized,
                (DeliveryRoleModel)item.DeliveryRole,
                item.DocumentTypeId,
                item.IsPrimary,
                item.IsActive,
                item.ValidFrom,
                item.ValidTo)).ToArray(),
        client.Partners.OrderBy(item => item.Role).ThenBy(item => item.FullName)
            .Select(item => new ClientPartnerModel(
                item.Id,
                item.FullName,
                item.CpfNormalized,
                (ClientPartnerRoleModel)item.Role,
                item.IsActive,
                item.EmailNormalized)).ToArray());

    private static MessageTemplateModel Map(MessageTemplate template) => new(
        template.Id,
        template.ClientId,
        template.DocumentTypeId,
        template.Name,
        template.SubjectTemplate,
        template.BodyTemplate,
        (SignatureModeModel)template.SignatureMode,
        template.IsDefault,
        template.IsActive,
        template.Version,
        template.UpdatedAtUtc);

    private static ClientCatalogDraft ToDomain(ClientMutationRequest request) => new(
        (PersonType)request.PersonType,
        request.LegalNameOrFullName,
        request.PreferredName,
        request.InternalCode,
        request.PrimaryTaxId,
        request.IsActive,
        request.DefaultSubjectTemplateId,
        request.DefaultBodyTemplateId,
        request.Notes,
        request.Identifiers.Select(ToDomain).ToArray(),
        request.Establishments.Select(ToDomain).ToArray(),
        request.Recipients.Select(ToDomain).ToArray(),
        (request.Partners ?? []).Select(ToDomain).ToArray());

    private static ClientCatalogDraft ToDomain(ClientDetails client) => new(
        (PersonType)client.PersonType,
        client.LegalNameOrFullName,
        client.PreferredName,
        client.InternalCode,
        client.PrimaryTaxId,
        client.IsActive,
        client.DefaultSubjectTemplateId,
        client.DefaultBodyTemplateId,
        client.Notes,
        client.Identifiers.Select(ToDomain).ToArray(),
        client.Establishments.Select(ToDomain).ToArray(),
        client.Recipients.Select(ToDomain).ToArray(),
        (client.Partners ?? []).Select(ToDomain).ToArray());

    private static ClientIdentifierDraft ToDomain(ClientIdentifierModel item) => new(
        item.Id,
        (ClientIdentifierType)item.Type,
        item.Value,
        (ClientIdentifierSemanticRole)item.SemanticRole,
        item.Priority,
        item.IsActive,
        item.IsUniqueWithinOrganization);

    private static EstablishmentDraft ToDomain(EstablishmentModel item) => new(
        item.Id,
        item.Cnpj,
        item.LegalName,
        item.DisplayName,
        item.InternalCode,
        item.IsHeadOffice,
        item.IsActive);

    private static RecipientDraft ToDomain(RecipientModel item) => new(
        item.Id,
        item.EstablishmentId,
        item.DisplayName,
        item.Email,
        (DeliveryRole)item.DeliveryRole,
        item.DocumentTypeId,
        item.IsPrimary,
        item.IsActive,
        item.ValidFrom,
        item.ValidTo);

    private static ClientPartnerDraft ToDomain(ClientPartnerModel item) => new(
        item.Id,
        item.FullName,
        item.Cpf,
        (ClientPartnerRole)item.Role,
        item.IsActive,
        item.Email);

    private static MessageTemplateDraft ToDomain(MessageTemplateMutationRequest request) => new(
        request.ClientId,
        request.DocumentTypeId,
        request.Name,
        request.SubjectTemplate,
        request.BodyTemplate,
        (SignatureMode)request.SignatureMode,
        request.IsDefault,
        request.IsActive);

    private static MessageTemplateDraft ToDomain(MessageTemplateModel template) => new(
        template.ClientId,
        template.DocumentTypeId,
        template.Name,
        template.SubjectTemplate,
        template.BodyTemplate,
        (SignatureMode)template.SignatureMode,
        template.IsDefault,
        template.IsActive);

    private static MessageTemplateMutationRequest ToMutation(
        MessageTemplateModel template,
        bool isActive) => new(
        template.Version,
        template.ClientId,
        template.DocumentTypeId,
        template.Name,
        template.SubjectTemplate,
        template.BodyTemplate,
        template.SignatureMode,
        template.IsDefault,
        isActive);

    private static T ValidateDomain<T>(Func<T> operation)
    {
        try
        {
            return operation();
        }
        catch (DomainValidationException exception)
        {
            throw new InvalidOperationException(exception.Message, exception);
        }
    }

    private static T DeserializeRequired<T>(string payload) =>
        JsonSerializer.Deserialize<T>(payload, SerializerOptions)
        ?? throw new JsonException("O catálogo local contém um registro vazio.");

    private static ReviewDocument DeserializeReviewDocument(string payload)
    {
        try
        {
            return DeserializeRequired<ReviewDocument>(payload);
        }
        catch (JsonException exception)
        {
            throw new InvalidOperationException(
                "Uma revisão antiga não pôde ser conferida. O cliente não foi excluído; peça ao suporte para revisar o catálogo.",
                exception);
        }
    }

    private static DispatchItem DeserializeDispatchItem(string payload)
    {
        try
        {
            return DeserializeRequired<DispatchItem>(payload);
        }
        catch (JsonException exception)
        {
            throw new InvalidOperationException(
                "Uma mensagem antiga não pôde ser conferida. O cliente não foi excluído; peça ao suporte para revisar o catálogo.",
                exception);
        }
    }

    private static void EnsureExpectedVersion(Guid id, long expected, long actual)
    {
        if (expected != actual)
        {
            throw Concurrency(id, expected, actual);
        }
    }

    private static HttpRequestException Concurrency(Guid id, long expected, long actual) => new(
        $"A entidade {id:D} está na versão {actual}; a versão {expected} foi solicitada.",
        null,
        HttpStatusCode.Conflict);

    private static string Digits(string value) => new(value.Where(char.IsAsciiDigit).ToArray());

    private sealed class TemplateNameKeyComparer : IEqualityComparer<(Guid? ClientId, string Name)>
    {
        public static TemplateNameKeyComparer Instance { get; } = new();

        public bool Equals(
            (Guid? ClientId, string Name) x,
            (Guid? ClientId, string Name) y) =>
            x.ClientId == y.ClientId && string.Equals(x.Name, y.Name, StringComparison.OrdinalIgnoreCase);

        public int GetHashCode((Guid? ClientId, string Name) obj) => HashCode.Combine(
            obj.ClientId,
            StringComparer.OrdinalIgnoreCase.GetHashCode(obj.Name));
    }
}
