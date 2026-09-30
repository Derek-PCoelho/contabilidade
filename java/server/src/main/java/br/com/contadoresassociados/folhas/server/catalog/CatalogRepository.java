package br.com.contadoresassociados.folhas.server.catalog;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.application.clients.CatalogMapping;
import br.com.contadoresassociados.folhas.application.clients.CatalogRules;
import br.com.contadoresassociados.folhas.application.clients.TemplatePlaceholderValidator;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportResult;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogTransferDocument;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientReadinessResponse;
import br.com.contadoresassociados.folhas.contracts.clients.ClientSearchResponse;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.Client;
import br.com.contadoresassociados.folhas.domain.clients.ClientOperationalReadiness;
import br.com.contadoresassociados.folhas.domain.clients.MessageTemplate;
import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.db.Sql;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import br.com.contadoresassociados.folhas.server.sync.SyncChanges;
import java.sql.Connection;
import java.sql.SQLException;
import br.com.contadoresassociados.folhas.application.common.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Porta do {@code ClientCatalogRepository} com as regras compartilhadas do perfil local
 * ({@link CatalogRules}/{@link CatalogMapping}) e as correções:
 * <ul>
 * <li>3.1 — toda gravação trava o contador da organização antes de gerar checkpoint;</li>
 * <li>3.2 — conflitos de serialização são repetidos pelo {@link Db};</li>
 * <li>3.5 — a simulação da importação roda a transação inteira e desfaz (pega unicidade/FK do
 * banco) e a sobrescrita exige que o registro não tenha mudado desde a exportação;</li>
 * <li>3.8 — busca sem acento via {@code folhas_search_key} + índice trigram;</li>
 * <li>4.17 — mensagens em português com códigos estáveis;</li>
 * <li>4.19 — ativar/inativar e arquivar cliente e modelo; consulta em lote.</li>
 * </ul>
 */
public final class CatalogRepository {

    public static final int CURRENT_TRANSFER_FORMAT_VERSION = 1;
    public static final int MAX_PAGE = 200;
    public static final int MAX_BATCH = 200;

    /** Resultado de uma gravação com o checkpoint gerado (para a notificação pós-commit). */
    public record Written<T>(T value, long checkpoint) {
    }

    /** Identidade de quem grava. */
    public record Actor(UUID organizationId, UUID userId, UUID deviceId) {
    }

    private final Db db;
    private final Clock clock;

    public CatalogRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ================================================================== consultas

    public ClientSearchResponse search(UUID organizationId, String search, Boolean active, PersonTypeModel personType,
            int skip, int take) {
        var safeSkip = Math.max(0, skip);
        var safeTake = Math.clamp(take, 1, MAX_PAGE);
        return db.tenantRead(organizationId, c -> {
            var where = new StringBuilder("c.\"OrganizationId\" = ? AND c.\"ArchivedAtUtc\" IS NULL");
            var args = new ArrayList<Object>();
            args.add(organizationId);
            if (active != null) {
                where.append(" AND c.\"IsActive\" = ?");
                args.add(active);
            }
            if (personType != null) {
                where.append(" AND c.\"PersonType\" = ?");
                args.add(Sql.pascal(personType));
            }
            if (search != null && !search.isBlank()) {
                var term = search.strip();
                var tax = taxTerm(term);
                where.append(" AND (folhas_search_key(c.\"LegalNameOrFullName\") LIKE '%' || folhas_search_key(?) || '%'"
                        + " OR folhas_search_key(c.\"PreferredName\") LIKE '%' || folhas_search_key(?) || '%'"
                        + " OR upper(c.\"InternalCode\") LIKE '%' || upper(?) || '%'");
                args.add(escapeLike(term));
                args.add(escapeLike(term));
                args.add(escapeLike(term));
                if (!tax.isEmpty()) {
                    where.append(" OR c.\"PrimaryTaxIdNormalized\" LIKE '%' || ? || '%'");
                    args.add(tax);
                }
                where.append(')');
            }
            int total;
            try (var st = c.prepareStatement("SELECT count(*) FROM clients c WHERE " + where)) {
                bind(st, args);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            var items = new ArrayList<ClientListItem>();
            try (var st = c.prepareStatement("SELECT c.\"Id\", c.\"PersonType\", c.\"LegalNameOrFullName\", c.\"PreferredName\", "
                    + "c.\"PrimaryTaxIdNormalized\", c.\"InternalCode\", c.\"IsActive\", c.\"Version\", c.\"UpdatedAtUtc\", "
                    + "(SELECT count(*) FROM client_establishments e WHERE e.\"ClientId\" = c.\"Id\" AND e.\"IsActive\") AS est, "
                    + "(SELECT count(*) FROM client_recipients r WHERE r.\"ClientId\" = c.\"Id\" AND r.\"IsActive\") AS rec "
                    + "FROM clients c WHERE " + where + " ORDER BY c.\"IsActive\" DESC, "
                    + "folhas_search_key(COALESCE(c.\"PreferredName\", c.\"LegalNameOrFullName\")), c.\"Id\" OFFSET ? LIMIT ?")) {
                var all = new ArrayList<>(args);
                all.add(safeSkip);
                all.add(safeTake);
                bind(st, all);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        var preferred = rs.getString("PreferredName");
                        items.add(new ClientListItem(Sql.uuid(rs, "Id"),
                                Sql.fromPascal(PersonTypeModel.class, rs.getString("PersonType")),
                                preferred != null && !preferred.isBlank() ? preferred : rs.getString("LegalNameOrFullName"),
                                mask(rs.getString("PrimaryTaxIdNormalized")), rs.getString("InternalCode"),
                                rs.getBoolean("IsActive"), rs.getLong("Version"), rs.getInt("est"), rs.getInt("rec"),
                                Sql.utc(rs, "UpdatedAtUtc")));
                    }
                }
            }
            return new ClientSearchResponse(items, total, safeSkip, safeTake);
        });
    }

    public Optional<ClientDetails> get(UUID organizationId, UUID clientId) {
        return db.tenantRead(organizationId, c -> CatalogStore.client(c, organizationId, clientId, false)
                .map(CatalogStore.ClientRow::details));
    }

    /** Pendência 6.13: consulta em lote, na ordem pedida, sem N+1. */
    public List<ClientDetails> getMany(UUID organizationId, List<UUID> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).distinct().limit(MAX_BATCH).toList();
        return db.tenantRead(organizationId, c -> {
            var byId = CatalogStore.clientsByIds(c, organizationId, distinct).stream()
                    .collect(Collectors.toMap(r -> r.details().id(), CatalogStore.ClientRow::details));
            return distinct.stream().map(byId::get).filter(Objects::nonNull).toList();
        });
    }

    public Optional<ClientReadinessResponse> readiness(UUID organizationId, UUID clientId, LocalDate today) {
        return get(organizationId, clientId).map(client -> {
            var legacy = CatalogRules.legacyEmailReadinessBlocks(client, today);
            if (!legacy.isEmpty()) {
                return new ClientReadinessResponse(client.id(), false, legacy);
            }
            var compatible = CatalogRules.removeInactiveLegacyInvalidEmails(client);
            var domain = domain(() -> new Client(client.id(), organizationId, CatalogMapping.toDomain(compatible),
                    systemActor(), client.updatedAtUtc().toInstant()));
            var result = ClientOperationalReadiness.evaluate(domain, today);
            return new ClientReadinessResponse(client.id(), result.eligible(), result.blockCodes());
        });
    }

    public List<MessageTemplateModel> templates(UUID organizationId, UUID clientId, boolean includeInactive) {
        return db.tenantRead(organizationId, c -> {
            var where = new StringBuilder("\"ArchivedAtUtc\" IS NULL");
            var args = new ArrayList<Object>();
            if (clientId != null) {
                where.append(" AND (\"ClientId\" IS NULL OR \"ClientId\" = ?)");
                args.add(clientId);
            }
            if (!includeInactive) {
                where.append(" AND \"IsActive\"");
            }
            return CatalogStore.templates(c, organizationId, where.toString(), args, false).stream()
                    .map(CatalogStore.TemplateRow::model).toList();
        });
    }

    public List<AuditEventModel> audit(UUID organizationId, UUID clientId, int take) {
        var safeTake = Math.clamp(take, 1, 200);
        return db.tenantRead(organizationId, c -> {
            var list = new ArrayList<AuditEventModel>();
            try (var st = c.prepareStatement("SELECT \"Id\", \"EntityType\", \"EntityId\", \"Action\", \"Category\", \"Severity\", "
                    + "\"RedactedDataJson\"::text AS data, \"TimestampUtc\", \"CorrelationId\" FROM audit_events WHERE "
                    + "\"OrganizationId\" = ? AND \"EntityType\" = 'client' AND \"EntityId\" = ? ORDER BY \"TimestampUtc\" DESC "
                    + "LIMIT ?")) {
                st.setObject(1, organizationId);
                st.setString(2, clientId.toString());
                st.setInt(3, safeTake);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        list.add(new AuditEventModel(Sql.uuid(rs, "Id"), rs.getString("EntityType"),
                                rs.getString("EntityId"), rs.getString("Action"), rs.getString("Category"),
                                rs.getString("Severity"), rs.getString("data"), Sql.utc(rs, "TimestampUtc"),
                                Sql.uuid(rs, "CorrelationId")));
                    }
                }
            }
            return list;
        });
    }

    /** Catálogo completo (não arquivado) numa única leitura, para a resolução de documentos. */
    public List<ClientDetails> allClients(UUID organizationId) {
        return db.tenantRead(organizationId, c -> CatalogStore.allClients(c, organizationId).stream()
                .map(CatalogStore.ClientRow::details).toList());
    }

    public ClientCatalogTransferDocument export(UUID organizationId) {
        return db.tenant(organizationId, Db.Isolation.REPEATABLE_READ, c -> new ClientCatalogTransferDocument(
                CURRENT_TRANSFER_FORMAT_VERSION, now(),
                CatalogStore.allClients(c, organizationId).stream().map(CatalogStore.ClientRow::details).toList(),
                CatalogStore.templates(c, organizationId, "\"ArchivedAtUtc\" IS NULL", List.of(), false).stream()
                        .map(CatalogStore.TemplateRow::model).toList()));
    }

    // ================================================================== clientes

    public Written<ClientDetails> createClient(Actor actor, ClientMutationRequest request, UUID correlationId) {
        Objects.requireNonNull(request, "request");
        if (request.expectedVersion() != 0) {
            throw CatalogRules.invalid("Um novo cliente deve usar a versão esperada zero.");
        }
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            validateTemplateReferences(c, actor.organizationId(), request.defaultSubjectTemplateId(),
                    request.defaultBodyTemplateId());
            var instant = clock.now();
            var domain = domain(() -> new Client(UUID.randomUUID(), actor.organizationId(), CatalogMapping.toDomain(request),
                    actor.userId(), instant));
            var saved = CatalogMapping.map(domain);
            CatalogRules.validateClientUniqueness(saved, others(c, actor.organizationId(), saved.id()));
            CatalogStore.writeClient(c, actor.organizationId(), saved, actor.userId(), actor.userId(), 0, true);
            var checkpoint = changeAndAudit(c, actor, saved, "created", correlationId, instant);
            return new Written<>(saved, checkpoint);
        });
    }

    public Written<ClientDetails> updateClient(Actor actor, UUID clientId, ClientMutationRequest request,
            UUID correlationId) {
        Objects.requireNonNull(request, "request");
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            var row = requireClient(c, actor.organizationId(), clientId);
            var existing = row.details();
            if (request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(clientId, request.expectedVersion(), existing.version());
            }
            if (existing.personType() != request.personType()) {
                throw CatalogRules.invalid("O tipo do cliente não pode ser alterado depois do cadastro. Arquive-o e crie "
                        + "outro cadastro se a identidade estiver incorreta.");
            }
            validateTemplateReferences(c, actor.organizationId(), request.defaultSubjectTemplateId(),
                    request.defaultBodyTemplateId());
            var instant = clock.now();
            var normalizedExisting = CatalogRules.quarantineMisplacedTaxIdentifiers(
                    CatalogRules.prepareLegacyEmailDataForMutation(existing, request));
            var normalizedRequest = CatalogRules.quarantineLegacyMisplacedTaxIdentifiers(existing,
                    CatalogRules.prepareLegacyEmailRequestForMutation(existing, request));
            var domain = domain(() -> {
                var client = Client.restore(existing.id(), actor.organizationId(),
                        CatalogMapping.toDomain(normalizedExisting), existing.version(), row.createdBy(),
                        existing.createdAtUtc().toInstant(), row.updatedBy(), existing.updatedAtUtc().toInstant());
                client.apply(CatalogMapping.toDomain(normalizedRequest), existing.version(), actor.userId(), instant);
                return client;
            });
            var saved = CatalogMapping.map(domain);
            CatalogRules.validateClientUniqueness(saved, others(c, actor.organizationId(), saved.id()));
            CatalogStore.writeClient(c, actor.organizationId(), saved, row.createdBy(), actor.userId(),
                    existing.version(), false);
            var action = existing.isActive() && !saved.isActive() ? "deactivated"
                    : !existing.isActive() && saved.isActive() ? "reactivated" : "updated";
            return new Written<>(saved, changeAndAudit(c, actor, saved, action, correlationId, instant));
        });
    }

    public Written<ClientDetails> setClientActive(Actor actor, UUID clientId, long expectedVersion, boolean active,
            UUID correlationId) {
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            var row = requireClient(c, actor.organizationId(), clientId);
            var existing = row.details();
            if (expectedVersion != existing.version()) {
                throw new CatalogException.Concurrency(clientId, expectedVersion, existing.version());
            }
            if (existing.isActive() == active) {
                return new Written<>(existing, 0L);
            }
            var instant = clock.now();
            var updated = CatalogMapping.withMeta(CatalogRules.quarantineMisplacedTaxIdentifiers(existing),
                    existing.version() + 1, existing.createdAtUtc(), utc(instant), active);
            if (active) {
                CatalogRules.validateClientUniqueness(updated, others(c, actor.organizationId(), clientId));
            }
            CatalogStore.writeClient(c, actor.organizationId(), updated, row.createdBy(), actor.userId(),
                    existing.version(), false);
            return new Written<>(updated, changeAndAudit(c, actor, updated, active ? "reactivated" : "deactivated",
                    correlationId, instant));
        });
    }

    public Written<Void> archiveClient(Actor actor, UUID clientId, ArchiveRequest request, UUID correlationId) {
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            var row = requireClient(c, actor.organizationId(), clientId);
            var existing = row.details();
            if (request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(clientId, request.expectedVersion(), existing.version());
            }
            if (existing.isActive()) {
                throw CatalogRules.invalid("Inative o cliente antes de excluí-lo da lista. Assim nenhuma operação em "
                        + "andamento perde a referência.");
            }
            if (!CatalogStore.templates(c, actor.organizationId(), "\"ClientId\" = ? AND \"ArchivedAtUtc\" IS NULL",
                    List.of(clientId), false).isEmpty()) {
                throw CatalogRules.invalid("Este cliente possui mensagens personalizadas. Exclua primeiro essas mensagens "
                        + "inativas.");
            }
            var instant = clock.now();
            try (var st = c.prepareStatement("UPDATE clients SET \"ArchivedAtUtc\" = ?, \"ArchivedBy\" = ?, "
                    + "\"ArchiveReason\" = ?, \"Version\" = \"Version\" + 1, \"UpdatedAtUtc\" = ?, \"UpdatedBy\" = ? "
                    + "WHERE \"Id\" = ? AND \"OrganizationId\" = ? AND \"Version\" = ?")) {
                Sql.instant(st, 1, instant);
                st.setObject(2, actor.userId());
                st.setString(3, reason(request));
                Sql.instant(st, 4, instant);
                st.setObject(5, actor.userId());
                st.setObject(6, clientId);
                st.setObject(7, actor.organizationId());
                st.setLong(8, existing.version());
                if (st.executeUpdate() != 1) {
                    throw new CatalogException.Concurrency(clientId, request.expectedVersion(), -1);
                }
            }
            var checkpoint = SyncChanges.record(c, actor.organizationId(), clientId, "client-catalog", instant);
            AuditLog.append(c, actor.organizationId(), actor.userId(), actor.deviceId(), "client", clientId.toString(),
                    "archived", "registration", "information", Map.of("version", existing.version() + 1), instant,
                    correlationId);
            return new Written<Void>(null, checkpoint);
        });
    }

    // ================================================================== modelos

    public Written<MessageTemplateModel> saveTemplate(Actor actor, UUID templateId,
            MessageTemplateMutationRequest request, UUID correlationId) {
        Objects.requireNonNull(request, "request");
        if (templateId == null && request.expectedVersion() != 0) {
            throw CatalogRules.invalid("Um novo modelo deve usar a versão esperada zero.");
        }
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            CatalogStore.TemplateRow existing = null;
            if (templateId != null) {
                existing = CatalogStore.template(c, actor.organizationId(), templateId, true)
                        .filter(t -> t.archivedAt() == null)
                        .orElseThrow(() -> new CatalogException.NotFound("O modelo que seria alterado"));
                if (request.expectedVersion() != existing.model().version()) {
                    throw new CatalogException.Concurrency(templateId, request.expectedVersion(),
                            existing.model().version());
                }
            }
            if (request.clientId() != null && CatalogStore.client(c, actor.organizationId(), request.clientId(), false)
                    .isEmpty()) {
                throw new CatalogException.NotFound("O cliente associado ao modelo");
            }
            if (!request.isActive()) {
                ensureTemplateIsNotReferencedAsDefault(c, actor.organizationId(), templateId);
            }
            CatalogRules.validateTemplateUniqueness(templateId, request, activeScopeTemplates(c, actor.organizationId()));
            var instant = clock.now();
            var id = templateId == null ? UUID.randomUUID() : templateId;
            var domain = domain(() -> new MessageTemplate(id, actor.organizationId(), CatalogMapping.toDomain(request),
                    actor.userId(), instant, TemplatePlaceholderValidator.INSTANCE));
            var version = existing == null ? 1 : existing.model().version() + 1;
            var saved = CatalogMapping.withMeta(CatalogMapping.map(domain), version, utc(instant));
            CatalogStore.writeTemplate(c, actor.organizationId(), saved, actor.userId(),
                    existing == null ? 0 : existing.model().version(), existing == null);
            return new Written<>(saved, templateChange(c, actor, saved, existing == null ? "created" : "updated",
                    correlationId, instant));
        });
    }

    public Written<MessageTemplateModel> setTemplateActive(Actor actor, UUID templateId, long expectedVersion,
            boolean active, UUID correlationId) {
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            var existing = CatalogStore.template(c, actor.organizationId(), templateId, true)
                    .filter(t -> t.archivedAt() == null)
                    .orElseThrow(() -> new CatalogException.NotFound("O modelo de mensagem")).model();
            if (expectedVersion != existing.version()) {
                throw new CatalogException.Concurrency(templateId, expectedVersion, existing.version());
            }
            if (existing.isActive() == active) {
                return new Written<>(existing, 0L);
            }
            if (!active) {
                ensureTemplateIsNotReferencedAsDefault(c, actor.organizationId(), templateId);
            }
            var request = CatalogMapping.toMutation(existing, active);
            CatalogRules.validateTemplateUniqueness(templateId, request, activeScopeTemplates(c, actor.organizationId()));
            var instant = clock.now();
            var updated = new MessageTemplateModel(existing.id(), existing.clientId(), existing.documentTypeId(),
                    existing.name(), existing.subjectTemplate(), existing.bodyTemplate(), existing.signatureMode(),
                    existing.isDefault(), active, existing.version() + 1, utc(instant));
            CatalogStore.writeTemplate(c, actor.organizationId(), updated, actor.userId(), existing.version(), false);
            return new Written<>(updated, templateChange(c, actor, updated, active ? "reactivated" : "deactivated",
                    correlationId, instant));
        });
    }

    public Written<Void> archiveTemplate(Actor actor, UUID templateId, ArchiveRequest request, UUID correlationId) {
        return db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, actor.organizationId());
            var existing = CatalogStore.template(c, actor.organizationId(), templateId, true)
                    .filter(t -> t.archivedAt() == null)
                    .orElseThrow(() -> new CatalogException.NotFound("O modelo de mensagem")).model();
            if (request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(templateId, request.expectedVersion(), existing.version());
            }
            if (existing.isActive()) {
                throw CatalogRules.invalid("Inative a mensagem antes de excluí-la da lista.");
            }
            ensureTemplateIsNotReferencedAsDefault(c, actor.organizationId(), templateId);
            var instant = clock.now();
            try (var st = c.prepareStatement("UPDATE message_templates SET \"ArchivedAtUtc\" = ?, \"ArchivedBy\" = ?, "
                    + "\"ArchiveReason\" = ?, \"Version\" = \"Version\" + 1, \"UpdatedAtUtc\" = ?, \"UpdatedBy\" = ? "
                    + "WHERE \"Id\" = ? AND \"OrganizationId\" = ? AND \"Version\" = ?")) {
                Sql.instant(st, 1, instant);
                st.setObject(2, actor.userId());
                st.setString(3, reason(request));
                Sql.instant(st, 4, instant);
                st.setObject(5, actor.userId());
                st.setObject(6, templateId);
                st.setObject(7, actor.organizationId());
                st.setLong(8, existing.version());
                st.executeUpdate();
            }
            var checkpoint = SyncChanges.record(c, actor.organizationId(), templateId, "message-template", instant);
            AuditLog.append(c, actor.organizationId(), actor.userId(), actor.deviceId(), "message-template",
                    templateId.toString(), "archived", "registration", "information",
                    Map.of("version", existing.version() + 1), instant, correlationId);
            return new Written<Void>(null, checkpoint);
        });
    }

    // ================================================================== importação

    public Written<ClientCatalogImportResult> importCatalog(Actor actor, ClientCatalogImportRequest request,
            UUID correlationId) {
        Objects.requireNonNull(request, "request");
        var document = request.document();
        if (document == null) {
            throw CatalogRules.invalid("A cópia de segurança está vazia.");
        }
        if (document.formatVersion() != CURRENT_TRANSFER_FORMAT_VERSION) {
            throw CatalogRules.invalid("A versão " + document.formatVersion() + " da cópia de segurança não é compatível.");
        }
        if (document.clients().stream().map(ClientDetails::id).distinct().count() != document.clients().size()
                || document.templates().stream().map(MessageTemplateModel::id).distinct().count()
                        != document.templates().size()) {
            throw CatalogRules.invalid("A cópia de segurança contém identificadores repetidos.");
        }
        Db.Work<Written<ClientCatalogImportResult>> work = c -> importInTransaction(c, actor, request, correlationId);
        return request.dryRun()
                ? db.tenantDryRun(actor.organizationId(), Db.Isolation.READ_COMMITTED, work)
                : db.tenant(actor.organizationId(), Db.Isolation.READ_COMMITTED, work);
    }

    private Written<ClientCatalogImportResult> importInTransaction(Connection c, Actor actor,
            ClientCatalogImportRequest request, UUID correlationId) throws SQLException {
        var org = actor.organizationId();
        SyncChanges.lock(c, org);
        var document = request.document();
        var existingClients = new HashMap<UUID, CatalogStore.ClientRow>();
        for (var row : CatalogStore.allClients(c, org)) {
            existingClients.put(row.details().id(), row);
        }
        var existingTemplates = new HashMap<UUID, MessageTemplateModel>();
        for (var row : CatalogStore.templates(c, org, "\"ArchivedAtUtc\" IS NULL", List.of(), false)) {
            existingTemplates.put(row.model().id(), row.model());
        }
        var workingClients = new LinkedHashMap<UUID, ClientDetails>();
        existingClients.forEach((id, row) -> workingClients.put(id, row.details()));
        var workingTemplates = new LinkedHashMap<>(existingTemplates);
        var changedClients = new ArrayList<ClientDetails>();
        var changedTemplates = new ArrayList<MessageTemplateModel>();
        var warnings = new ArrayList<String>();
        int clientsCreated = 0;
        int clientsUpdated = 0;
        int templatesCreated = 0;
        int templatesUpdated = 0;
        var instant = clock.now();
        for (var imported : document.clients()) {
            var existing = existingClients.get(imported.id());
            if (existing != null) {
                if (!request.overwriteExisting()) {
                    warnings.add("O cliente " + imported.id() + " já existe e foi ignorado.");
                    continue;
                }
                // 3.5: a sobrescrita não apaga edição feita depois da exportação
                if (existing.details().version() != imported.version()) {
                    warnings.add("O cliente " + imported.id() + " foi alterado depois da exportação (versão "
                            + existing.details().version() + ", cópia " + imported.version() + ") e não foi sobrescrito.");
                    continue;
                }
                if (existing.details().personType() != imported.personType()) {
                    throw CatalogRules.invalid("O cliente " + imported.id() + " mudaria de tipo (PF/PJ) na importação.");
                }
            }
            var domain = domain(() -> new Client(imported.id(), org, CatalogMapping.toDomain(imported), actor.userId(),
                    instant));
            var mapped = CatalogMapping.map(domain);
            var normalized = CatalogMapping.withMeta(mapped, existing == null ? 1 : existing.details().version() + 1,
                    existing == null ? utc(instant) : existing.details().createdAtUtc(), utc(instant), mapped.isActive());
            workingClients.put(normalized.id(), normalized);
            changedClients.add(normalized);
            if (existing == null) {
                clientsCreated++;
            } else {
                clientsUpdated++;
            }
        }
        for (var imported : document.templates()) {
            var existing = existingTemplates.get(imported.id());
            if (existing != null) {
                if (!request.overwriteExisting()) {
                    warnings.add("O modelo " + imported.id() + " já existe e foi ignorado.");
                    continue;
                }
                if (existing.version() != imported.version()) {
                    warnings.add("O modelo " + imported.id() + " foi alterado depois da exportação e não foi sobrescrito.");
                    continue;
                }
            }
            var domain = domain(() -> new MessageTemplate(imported.id(), org, CatalogMapping.toDomain(imported),
                    actor.userId(), instant, TemplatePlaceholderValidator.INSTANCE));
            var normalized = CatalogMapping.withMeta(CatalogMapping.map(domain),
                    existing == null ? 1 : existing.version() + 1, utc(instant));
            workingTemplates.put(normalized.id(), normalized);
            changedTemplates.add(normalized);
            if (existing == null) {
                templatesCreated++;
            } else {
                templatesUpdated++;
            }
        }
        CatalogRules.validateImportedCatalog(List.copyOf(workingClients.values()), List.copyOf(workingTemplates.values()));
        long checkpoint = 0;
        // as FKs cliente <-> modelo são adiáveis (conferidas no commit), então a ordem não importa
        for (var client : changedClients) {
            var existing = existingClients.get(client.id());
            if (!insertOrUpdateClient(c, org, client, existing, actor.userId())) {
                throw CatalogRules.invalid("O identificador do cliente " + client.id()
                        + " já pertence a outro cadastro fora desta organização.");
            }
            checkpoint = changeAndAudit(c, actor, client, "imported", correlationId, instant);
        }
        for (var template : changedTemplates) {
            var existing = existingTemplates.get(template.id());
            if (!insertOrUpdateTemplate(c, org, template, existing, actor.userId())) {
                throw CatalogRules.invalid("O identificador do modelo " + template.id()
                        + " já pertence a outro cadastro fora desta organização.");
            }
            checkpoint = templateChange(c, actor, template, "imported", correlationId, instant);
        }
        return new Written<>(new ClientCatalogImportResult(request.dryRun(), clientsCreated, clientsUpdated,
                templatesCreated, templatesUpdated, warnings), request.dryRun() ? 0 : checkpoint);
    }

    private static boolean insertOrUpdateClient(Connection c, UUID org, ClientDetails client,
            CatalogStore.ClientRow existing, UUID userId) throws SQLException {
        if (existing != null) {
            CatalogStore.writeClient(c, org, client, existing.createdBy(), userId, existing.details().version(), false);
            return true;
        }
        var savepoint = c.setSavepoint();
        try {
            CatalogStore.writeClient(c, org, client, userId, userId, 0, true);
            c.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            if (!Db.isUniqueViolation(e) || !e.getMessage().contains("PK_clients")) {
                throw e;
            }
            c.rollback(savepoint);
            return false;
        }
    }

    private static boolean insertOrUpdateTemplate(Connection c, UUID org, MessageTemplateModel template,
            MessageTemplateModel existing, UUID userId) throws SQLException {
        if (existing != null) {
            CatalogStore.writeTemplate(c, org, template, userId, existing.version(), false);
            return true;
        }
        var savepoint = c.setSavepoint();
        try {
            CatalogStore.writeTemplate(c, org, template, userId, 0, true);
            c.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            if (!Db.isUniqueViolation(e) || !e.getMessage().contains("PK_message_templates")) {
                throw e;
            }
            c.rollback(savepoint);
            return false;
        }
    }

    // ================================================================== auxiliares

    public long latestCheckpoint(UUID organizationId) {
        return db.tenantRead(organizationId, c -> SyncChanges.latest(c, organizationId));
    }

    private static CatalogStore.ClientRow requireClient(Connection c, UUID org, UUID id) throws SQLException {
        return CatalogStore.client(c, org, id, true).filter(r -> r.archivedAt() == null)
                .orElseThrow(() -> new CatalogException.NotFound("O cliente"));
    }

    private static List<ClientDetails> others(Connection c, UUID org, UUID id) throws SQLException {
        return CatalogStore.allClients(c, org).stream().map(CatalogStore.ClientRow::details)
                .filter(o -> !o.id().equals(id)).toList();
    }

    private static List<MessageTemplateModel> activeScopeTemplates(Connection c, UUID org) throws SQLException {
        return CatalogStore.templates(c, org, "\"ArchivedAtUtc\" IS NULL", List.of(), false).stream()
                .map(CatalogStore.TemplateRow::model).toList();
    }

    private static void validateTemplateReferences(Connection c, UUID org, UUID subject, UUID body)
            throws SQLException {
        var templates = activeScopeTemplates(c, org);
        CatalogRules.validateTemplateReferences(subject, body, templates, null);
    }

    private static void ensureTemplateIsNotReferencedAsDefault(Connection c, UUID org, UUID templateId)
            throws SQLException {
        if (templateId == null) {
            return;
        }
        try (var st = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM clients WHERE \"OrganizationId\" = ? AND "
                + "\"ArchivedAtUtc\" IS NULL AND (\"DefaultSubjectTemplateId\" = ? OR \"DefaultBodyTemplateId\" = ?))")) {
            st.setObject(1, org);
            st.setObject(2, templateId);
            st.setObject(3, templateId);
            try (var rs = st.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    throw CatalogRules.invalid("Esta mensagem está definida como padrão em um cliente. Escolha outro "
                            + "padrão antes de inativar ou excluir.");
                }
            }
        }
    }

    private static long changeAndAudit(Connection c, Actor actor, ClientDetails client, String action,
            UUID correlationId, Instant now) throws SQLException {
        var checkpoint = SyncChanges.record(c, actor.organizationId(), client.id(), "client-catalog", now);
        var data = new LinkedHashMap<String, Object>();
        data.put("Version", client.version());
        data.put("IsActive", client.isActive());
        data.put("IdentifierCount", client.identifiers().size());
        data.put("EstablishmentCount", client.establishments().size());
        data.put("RecipientCount", client.recipients().size());
        data.put("PartnerCount", client.partners().size());
        AuditLog.append(c, actor.organizationId(), actor.userId(), actor.deviceId(), "client", client.id().toString(),
                action, "registration", "information", data, now, correlationId);
        return checkpoint;
    }

    private static long templateChange(Connection c, Actor actor, MessageTemplateModel template, String action,
            UUID correlationId, Instant now) throws SQLException {
        var checkpoint = SyncChanges.record(c, actor.organizationId(), template.id(), "message-template", now);
        var data = new LinkedHashMap<String, Object>();
        data.put("Version", template.version());
        data.put("IsActive", template.isActive());
        data.put("IsDefault", template.isDefault());
        data.put("SignatureMode", Sql.pascal(template.signatureMode()));
        AuditLog.append(c, actor.organizationId(), actor.userId(), actor.deviceId(), "message-template",
                template.id().toString(), action, "registration", "information", data, now, correlationId);
        return checkpoint;
    }

    private static <T> T domain(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DomainValidationException e) {
            throw new CatalogException(e.code(), e.getMessage());
        } catch (ConcurrencyConflictException e) {
            throw new CatalogException("catalog.concurrency", e.getMessage());
        }
    }

    private static UUID systemActor() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private OffsetDateTime now() {
        return utc(clock.now());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String reason(ArchiveRequest request) {
        var r = request.reason();
        if (r == null || r.isBlank()) {
            return null;
        }
        var s = r.strip();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static String mask(String taxId) {
        try {
            return BrazilianRegistration.mask(taxId);
        } catch (RuntimeException e) {
            return "***";
        }
    }

    /** Termo fiscal: letras e dígitos em maiúsculas quando há pelo menos um dígito (CNPJ alfanumérico). */
    static String taxTerm(String value) {
        var sb = new StringBuilder();
        for (var ch : value.toCharArray()) {
            if (Character.isDigit(ch) || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                sb.append(Character.toUpperCase(ch));
            }
        }
        var s = sb.toString();
        return s.chars().anyMatch(Character::isDigit) ? s : "";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void bind(java.sql.PreparedStatement st, List<Object> args) throws SQLException {
        for (var i = 0; i < args.size(); i++) {
            st.setObject(i + 1, args.get(i));
        }
    }
}
