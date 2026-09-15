using System.Data;
using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Central;

public sealed class ClientCatalogRepository(
    FolhasDbContext dbContext,
    IClock clock) : IClientCatalogRepository
{
    private const int CurrentTransferFormatVersion = 1;

    public async Task<ClientSearchResponse> SearchAsync(
        Guid organizationId,
        string? search,
        bool? isActive,
        PersonTypeModel? personType,
        int skip,
        int take,
        CancellationToken cancellationToken)
    {
        var normalizedSkip = Math.Max(0, skip);
        var normalizedTake = Math.Clamp(take, 1, 200);
        var query = dbContext.Clients.AsNoTracking()
            .Where(client => client.OrganizationId == organizationId);

        if (isActive is not null)
        {
            query = query.Where(client => client.IsActive == isActive);
        }

        if (personType is not null)
        {
            var domainPersonType = ToDomain(personType.Value);
            query = query.Where(client => client.PersonType == domainPersonType);
        }

        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = search.Trim().ToUpperInvariant();
            var digits = new string(term.Where(char.IsAsciiDigit).ToArray());
            // The parameterless string members are intentionally kept inside the EF expression;
            // both configured providers translate them to SQL and cannot translate comparison overloads.
#pragma warning disable CA1304, CA1311, CA1862
            query = query.Where(client =>
                client.LegalNameOrFullName.ToUpper().Contains(term) ||
                (client.PreferredName != null && client.PreferredName.ToUpper().Contains(term)) ||
                (client.InternalCode != null && client.InternalCode.Contains(term)) ||
                (digits.Length > 0 && client.PrimaryTaxIdNormalized.Contains(digits)));
#pragma warning restore CA1304, CA1311, CA1862
        }

        var total = await query.CountAsync(cancellationToken);
        var clients = await query
            .OrderByDescending(client => client.IsActive)
            .ThenBy(client => client.LegalNameOrFullName)
            .Skip(normalizedSkip)
            .Take(normalizedTake)
            .Select(client => new
            {
                client.Id,
                client.PersonType,
                client.LegalNameOrFullName,
                client.PreferredName,
                client.PrimaryTaxIdNormalized,
                client.InternalCode,
                client.IsActive,
                client.Version,
                EstablishmentCount = client.Establishments.Count(item => item.IsActive),
                RecipientCount = client.Recipients.Count(item => item.IsActive),
                client.UpdatedAtUtc,
            })
            .ToArrayAsync(cancellationToken);

        return new ClientSearchResponse(
            clients.Select(client => new ClientListItem(
                client.Id,
                ToModel(client.PersonType),
                client.PreferredName ?? client.LegalNameOrFullName,
                BrazilianRegistration.Mask(client.PrimaryTaxIdNormalized),
                client.InternalCode,
                client.IsActive,
                client.Version,
                client.EstablishmentCount,
                client.RecipientCount,
                client.UpdatedAtUtc)).ToArray(),
            total,
            normalizedSkip,
            normalizedTake);
    }

    public async Task<ClientDetails?> GetAsync(
        Guid organizationId,
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var client = await ClientQuery(tracking: false).SingleOrDefaultAsync(
            item => item.OrganizationId == organizationId && item.Id == clientId,
            cancellationToken);
        return client is null ? null : Map(client);
    }

    public async Task<ClientDetails> CreateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        ClientMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken)
    {
        if (request.ExpectedVersion != 0)
        {
            throw new CatalogValidationException("A new client must use expected version zero.");
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        try
        {
            await ValidateTemplateReferencesAsync(organizationId, request, cancellationToken);
            var client = new Client(
                Guid.NewGuid(),
                organizationId,
                ToDomain(request),
                userId,
                clock.UtcNow);
            dbContext.Clients.Add(client);
            AddChangeAndAudit(client, userId, deviceId, "created", correlationId);
            await dbContext.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            return Map(client);
        }
        catch (DomainValidationException exception)
        {
            throw new CatalogValidationException(exception.Message);
        }
        catch (DbUpdateException exception)
        {
            throw UniqueConstraint(exception);
        }
    }

    public async Task<ClientDetails?> UpdateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        Guid clientId,
        ClientMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        var client = await ClientQuery(tracking: true).SingleOrDefaultAsync(
            item => item.OrganizationId == organizationId && item.Id == clientId,
            cancellationToken);
        if (client is null)
        {
            return null;
        }

        var wasActive = client.IsActive;
        try
        {
            await ValidateTemplateReferencesAsync(organizationId, request, cancellationToken);
            client.Apply(ToDomain(request), request.ExpectedVersion, userId, clock.UtcNow);
            var action = wasActive && !client.IsActive ? "deactivated" : "updated";
            AddChangeAndAudit(client, userId, deviceId, action, correlationId);
            await dbContext.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            return Map(client);
        }
        catch (ConcurrencyConflictException exception)
        {
            throw new CatalogConcurrencyException(
                exception.EntityId,
                exception.ExpectedVersion,
                exception.CurrentVersion);
        }
        catch (DbUpdateConcurrencyException)
        {
            throw new CatalogConcurrencyException(client.Id, request.ExpectedVersion, client.Version);
        }
        catch (DomainValidationException exception)
        {
            throw new CatalogValidationException(exception.Message);
        }
        catch (DbUpdateException exception)
        {
            throw UniqueConstraint(exception);
        }
    }

    public async Task<ClientReadinessResponse?> GetReadinessAsync(
        Guid organizationId,
        Guid clientId,
        DateOnly today,
        CancellationToken cancellationToken)
    {
        var client = await ClientQuery(tracking: false).SingleOrDefaultAsync(
            item => item.OrganizationId == organizationId && item.Id == clientId,
            cancellationToken);
        if (client is null)
        {
            return null;
        }

        var readiness = ClientOperationalReadinessEvaluator.Evaluate(client, today);
        return new ClientReadinessResponse(client.Id, readiness.IsEligible, readiness.BlockCodes);
    }

    public async Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid organizationId,
        Guid? clientId,
        bool includeInactive,
        CancellationToken cancellationToken)
    {
        var query = dbContext.MessageTemplates.AsNoTracking()
            .Where(template => template.OrganizationId == organizationId);
        if (clientId is not null)
        {
            query = query.Where(template => template.ClientId == null || template.ClientId == clientId);
        }

        if (!includeInactive)
        {
            query = query.Where(template => template.IsActive);
        }

        var templates = await query.OrderBy(template => template.Name).ToArrayAsync(cancellationToken);
        return templates.Select(Map).ToArray();
    }

    public async Task<MessageTemplateModel> CreateTemplateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        MessageTemplateMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken)
    {
        if (request.ExpectedVersion != 0)
        {
            throw new CatalogValidationException("A new template must use expected version zero.");
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        try
        {
            await ValidateTemplateClientAsync(organizationId, request.ClientId, cancellationToken);
            await ValidateDefaultTemplateAsync(organizationId, null, request, cancellationToken);
            var template = new MessageTemplate(
                Guid.NewGuid(),
                organizationId,
                ToDomain(request),
                userId,
                clock.UtcNow);
            dbContext.MessageTemplates.Add(template);
            AddChangeAndAudit(template, userId, deviceId, "created", correlationId);
            await dbContext.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            return Map(template);
        }
        catch (DomainValidationException exception)
        {
            throw new CatalogValidationException(exception.Message);
        }
        catch (DbUpdateException exception)
        {
            throw UniqueConstraint(exception);
        }
    }

    public async Task<MessageTemplateModel?> UpdateTemplateAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        Guid templateId,
        MessageTemplateMutationRequest request,
        Guid correlationId,
        CancellationToken cancellationToken)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        var template = await dbContext.MessageTemplates.SingleOrDefaultAsync(
            item => item.OrganizationId == organizationId && item.Id == templateId,
            cancellationToken);
        if (template is null)
        {
            return null;
        }

        try
        {
            await ValidateTemplateClientAsync(organizationId, request.ClientId, cancellationToken);
            await ValidateDefaultTemplateAsync(organizationId, templateId, request, cancellationToken);
            template.Apply(ToDomain(request), request.ExpectedVersion, userId, clock.UtcNow);
            AddChangeAndAudit(template, userId, deviceId, "updated", correlationId);
            await dbContext.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            return Map(template);
        }
        catch (ConcurrencyConflictException exception)
        {
            throw new CatalogConcurrencyException(
                exception.EntityId,
                exception.ExpectedVersion,
                exception.CurrentVersion);
        }
        catch (DbUpdateConcurrencyException)
        {
            throw new CatalogConcurrencyException(template.Id, request.ExpectedVersion, template.Version);
        }
        catch (DomainValidationException exception)
        {
            throw new CatalogValidationException(exception.Message);
        }
        catch (DbUpdateException exception)
        {
            throw UniqueConstraint(exception);
        }
    }

    public async Task<ClientCatalogTransferDocument> ExportAsync(
        Guid organizationId,
        CancellationToken cancellationToken)
    {
        var clients = await ClientQuery(tracking: false)
            .Where(client => client.OrganizationId == organizationId)
            .OrderBy(client => client.LegalNameOrFullName)
            .ToArrayAsync(cancellationToken);
        var templates = await dbContext.MessageTemplates.AsNoTracking()
            .Where(template => template.OrganizationId == organizationId)
            .OrderBy(template => template.Name)
            .ToArrayAsync(cancellationToken);
        return new ClientCatalogTransferDocument(
            CurrentTransferFormatVersion,
            clock.UtcNow,
            clients.Select(Map).ToArray(),
            templates.Select(Map).ToArray());
    }

    public async Task<ClientCatalogImportResult> ImportAsync(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        ClientCatalogImportRequest request,
        Guid correlationId,
        CancellationToken cancellationToken)
    {
        if (request.Document.FormatVersion != CurrentTransferFormatVersion)
        {
            throw new CatalogValidationException(
                $"Unsupported catalog format version {request.Document.FormatVersion}.");
        }

        if (request.Document.Clients.Select(item => item.Id).Distinct().Count() != request.Document.Clients.Count ||
            request.Document.Templates.Select(item => item.Id).Distinct().Count() != request.Document.Templates.Count)
        {
            throw new CatalogValidationException("The import contains duplicate entity identifiers.");
        }

        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        var existingClients = await ClientQuery(tracking: true)
            .Where(client => client.OrganizationId == organizationId)
            .ToDictionaryAsync(client => client.Id, cancellationToken);
        var existingTemplates = await dbContext.MessageTemplates
            .Where(template => template.OrganizationId == organizationId)
            .ToDictionaryAsync(template => template.Id, cancellationToken);
        var validClientIds = existingClients.Keys
            .Concat(request.Document.Clients.Select(client => client.Id))
            .ToHashSet();
        if (request.Document.Templates.Any(template =>
                template.ClientId is { } clientId && !validClientIds.Contains(clientId)))
        {
            throw new CatalogValidationException(
                "Every imported template client must exist in the authenticated organization or import.");
        }

        var activeTemplateIds = existingTemplates.Values
            .Where(template => template.IsActive &&
                request.Document.Templates.All(imported => imported.Id != template.Id))
            .Select(template => template.Id)
            .Concat(request.Document.Templates.Where(template => template.IsActive).Select(template => template.Id))
            .ToHashSet();
        if (request.Document.Clients.Any(client =>
                (client.DefaultSubjectTemplateId is { } subjectId && !activeTemplateIds.Contains(subjectId)) ||
                (client.DefaultBodyTemplateId is { } bodyId && !activeTemplateIds.Contains(bodyId))))
        {
            throw new CatalogValidationException(
                "Imported default templates must be active and belong to the same catalog.");
        }

        var duplicateDefaults = existingTemplates.Values
            .Where(template => template.IsActive &&
                template.IsDefault &&
                request.Document.Templates.All(imported => imported.Id != template.Id))
            .Select(template => (template.ClientId, template.DocumentTypeId))
            .Concat(request.Document.Templates
                .Where(template => template.IsActive && template.IsDefault)
                .Select(template => (template.ClientId, template.DocumentTypeId)))
            .GroupBy(item => item)
            .Any(group => group.Count() > 1);
        if (duplicateDefaults)
        {
            throw new CatalogValidationException(
                "The import would create more than one active default template for a scope.");
        }

        var clientsCreated = 0;
        var clientsUpdated = 0;
        var templatesCreated = 0;
        var templatesUpdated = 0;
        var warnings = new List<string>();

        try
        {
            foreach (var imported in request.Document.Clients)
            {
                var draft = ToDomain(imported);
                if (existingClients.TryGetValue(imported.Id, out var existing))
                {
                    if (!request.OverwriteExisting)
                    {
                        warnings.Add($"Client {imported.Id:D} already exists and was skipped.");
                        continue;
                    }

                    existing.Apply(draft, existing.Version, userId, clock.UtcNow);
                    AddChangeAndAudit(existing, userId, deviceId, "imported", correlationId);
                    clientsUpdated++;
                }
                else
                {
                    var client = new Client(imported.Id, organizationId, draft, userId, clock.UtcNow);
                    dbContext.Clients.Add(client);
                    AddChangeAndAudit(client, userId, deviceId, "imported", correlationId);
                    clientsCreated++;
                }
            }

            foreach (var imported in request.Document.Templates)
            {
                var draft = ToDomain(imported);
                if (existingTemplates.TryGetValue(imported.Id, out var existing))
                {
                    if (!request.OverwriteExisting)
                    {
                        warnings.Add($"Template {imported.Id:D} already exists and was skipped.");
                        continue;
                    }

                    existing.Apply(draft, existing.Version, userId, clock.UtcNow);
                    AddChangeAndAudit(existing, userId, deviceId, "imported", correlationId);
                    templatesUpdated++;
                }
                else
                {
                    var template = new MessageTemplate(
                        imported.Id,
                        organizationId,
                        draft,
                        userId,
                        clock.UtcNow);
                    dbContext.MessageTemplates.Add(template);
                    AddChangeAndAudit(template, userId, deviceId, "imported", correlationId);
                    templatesCreated++;
                }
            }

            if (request.DryRun)
            {
                await transaction.RollbackAsync(cancellationToken);
            }
            else
            {
                await dbContext.SaveChangesAsync(cancellationToken);
                await transaction.CommitAsync(cancellationToken);
            }
        }
        catch (DomainValidationException exception)
        {
            throw new CatalogValidationException(exception.Message);
        }
        catch (DbUpdateException exception)
        {
            throw UniqueConstraint(exception);
        }

        return new ClientCatalogImportResult(
            request.DryRun,
            clientsCreated,
            clientsUpdated,
            templatesCreated,
            templatesUpdated,
            warnings);
    }

    public async Task<IReadOnlyList<AuditEventModel>> GetClientAuditAsync(
        Guid organizationId,
        Guid clientId,
        int take,
        CancellationToken cancellationToken)
    {
        var entityId = clientId.ToString("D");
        var normalizedTake = Math.Clamp(take, 1, 200);
        var query = dbContext.AuditEvents.AsNoTracking()
            .Where(audit => audit.OrganizationId == organizationId &&
                audit.EntityType == "client" &&
                audit.EntityId == entityId);
        if (dbContext.Database.IsSqlite())
        {
            var sqliteEvents = await query.Take(1000)
                .Select(audit => new AuditEventModel(
                    audit.Id,
                    audit.EntityType,
                    audit.EntityId,
                    audit.Action,
                    audit.Category,
                    audit.Severity,
                    audit.RedactedDataJson,
                    audit.TimestampUtc,
                    audit.CorrelationId))
                .ToArrayAsync(cancellationToken);
            return sqliteEvents.OrderByDescending(audit => audit.TimestampUtc)
                .Take(normalizedTake)
                .ToArray();
        }

        return await query.OrderByDescending(audit => audit.TimestampUtc)
            .Take(normalizedTake)
            .Select(audit => new AuditEventModel(
                audit.Id,
                audit.EntityType,
                audit.EntityId,
                audit.Action,
                audit.Category,
                audit.Severity,
                audit.RedactedDataJson,
                audit.TimestampUtc,
                audit.CorrelationId))
            .ToArrayAsync(cancellationToken);
    }

    public Task<long> GetLatestCheckpointAsync(
        Guid organizationId,
        CancellationToken cancellationToken) =>
        dbContext.SyncChanges.AsNoTracking()
            .Where(change => change.OrganizationId == organizationId)
            .Select(change => change.Checkpoint)
            .DefaultIfEmpty()
            .MaxAsync(cancellationToken);

    private IQueryable<Client> ClientQuery(bool tracking)
    {
        var query = tracking ? dbContext.Clients : dbContext.Clients.AsNoTracking();
        return query
            .Include(client => client.Identifiers)
            .Include(client => client.Establishments)
            .Include(client => client.Recipients)
            .Include(client => client.Partners);
    }

    private async Task ValidateTemplateReferencesAsync(
        Guid organizationId,
        ClientMutationRequest request,
        CancellationToken cancellationToken)
    {
        var templateIds = new[] { request.DefaultSubjectTemplateId, request.DefaultBodyTemplateId }
            .Where(id => id is not null && id != Guid.Empty)
            .Select(id => id!.Value)
            .Distinct()
            .ToArray();
        if (templateIds.Length == 0)
        {
            return;
        }

        var count = await dbContext.MessageTemplates.AsNoTracking().CountAsync(
            template => template.OrganizationId == organizationId &&
                template.IsActive &&
                templateIds.Contains(template.Id),
            cancellationToken);
        if (count != templateIds.Length)
        {
            throw new CatalogValidationException(
                "Default templates must be active and belong to the authenticated organization.");
        }
    }

    private async Task ValidateTemplateClientAsync(
        Guid organizationId,
        Guid? clientId,
        CancellationToken cancellationToken)
    {
        if (clientId is null || clientId == Guid.Empty)
        {
            return;
        }

        if (!await dbContext.Clients.AsNoTracking().AnyAsync(
                client => client.OrganizationId == organizationId && client.Id == clientId,
                cancellationToken))
        {
            throw new CatalogValidationException(
                "Template client must belong to the authenticated organization.");
        }
    }

    private async Task ValidateDefaultTemplateAsync(
        Guid organizationId,
        Guid? templateId,
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken)
    {
        if (!request.IsActive || !request.IsDefault)
        {
            return;
        }

        var duplicate = await dbContext.MessageTemplates.AsNoTracking().AnyAsync(
            template => template.OrganizationId == organizationId &&
                template.Id != templateId &&
                template.ClientId == request.ClientId &&
                template.DocumentTypeId == request.DocumentTypeId &&
                template.IsActive &&
                template.IsDefault,
            cancellationToken);
        if (duplicate)
        {
            throw new CatalogValidationException(
                "Only one active default template is allowed for the same client and document type.");
        }
    }

    private void AddChangeAndAudit(
        Client client,
        Guid userId,
        Guid? deviceId,
        string action,
        Guid correlationId)
    {
        dbContext.SyncChanges.Add(new SyncChange
        {
            OrganizationId = client.OrganizationId,
            EntityId = client.Id,
            EntityType = "client-catalog",
            ChangedAtUtc = clock.UtcNow,
        });
        AddAudit(
            client.OrganizationId,
            userId,
            deviceId,
            "client",
            client.Id,
            action,
            new
            {
                client.Version,
                client.IsActive,
                IdentifierCount = client.Identifiers.Count,
                EstablishmentCount = client.Establishments.Count,
                RecipientCount = client.Recipients.Count,
                PartnerCount = client.Partners.Count,
            },
            correlationId);
    }

    private void AddChangeAndAudit(
        MessageTemplate template,
        Guid userId,
        Guid? deviceId,
        string action,
        Guid correlationId)
    {
        dbContext.SyncChanges.Add(new SyncChange
        {
            OrganizationId = template.OrganizationId,
            EntityId = template.Id,
            EntityType = "message-template",
            ChangedAtUtc = clock.UtcNow,
        });
        AddAudit(
            template.OrganizationId,
            userId,
            deviceId,
            "message-template",
            template.Id,
            action,
            new { template.Version, template.IsActive, template.IsDefault, template.SignatureMode },
            correlationId);
    }

    private void AddAudit(
        Guid organizationId,
        Guid userId,
        Guid? deviceId,
        string entityType,
        Guid entityId,
        string action,
        object redactedData,
        Guid correlationId) =>
        dbContext.AuditEvents.Add(new AuditEvent
        {
            Id = Guid.NewGuid(),
            OrganizationId = organizationId,
            UserId = userId,
            DeviceId = deviceId,
            EntityType = entityType,
            EntityId = entityId.ToString("D"),
            Action = action,
            Category = "registration",
            Severity = "information",
            RedactedDataJson = JsonSerializer.Serialize(redactedData),
            TimestampUtc = clock.UtcNow,
            CorrelationId = correlationId,
        });

    private static CatalogValidationException UniqueConstraint(DbUpdateException exception) =>
        new("A unique organization-scoped catalog value is already registered.");

    private static ClientDetails Map(Client client) => new(
        client.Id,
        ToModel(client.PersonType),
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
            ToModel(item.Type),
            item.ValueNormalized,
            ToModel(item.SemanticRole),
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
                ToModel(item.DeliveryRole),
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
                ToModel(item.Role),
                item.IsActive,
                item.EmailNormalized)).ToArray());

    private static MessageTemplateModel Map(MessageTemplate template) => new(
        template.Id,
        template.ClientId,
        template.DocumentTypeId,
        template.Name,
        template.SubjectTemplate,
        template.BodyTemplate,
        ToModel(template.SignatureMode),
        template.IsDefault,
        template.IsActive,
        template.Version,
        template.UpdatedAtUtc);

    private static ClientCatalogDraft ToDomain(ClientMutationRequest request) => new(
        ToDomain(request.PersonType),
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
        ToDomain(client.PersonType),
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

    private static PersonType ToDomain(PersonTypeModel value) => (PersonType)value;
    private static PersonTypeModel ToModel(PersonType value) => (PersonTypeModel)value;
    private static ClientIdentifierTypeModel ToModel(ClientIdentifierType value) =>
        (ClientIdentifierTypeModel)value;
    private static ClientIdentifierSemanticRoleModel ToModel(ClientIdentifierSemanticRole value) =>
        (ClientIdentifierSemanticRoleModel)value;
    private static DeliveryRoleModel ToModel(DeliveryRole value) => (DeliveryRoleModel)value;
    private static ClientPartnerRoleModel ToModel(ClientPartnerRole value) =>
        (ClientPartnerRoleModel)value;
    private static SignatureModeModel ToModel(SignatureMode value) => (SignatureModeModel)value;
}
