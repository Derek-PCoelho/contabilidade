package br.com.contadoresassociados.folhas.infrastructure.clients;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.application.clients.CatalogMapping;
import br.com.contadoresassociados.folhas.application.clients.CatalogRules;
import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.clients.TemplatePlaceholderValidator;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.ClientMatcher;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportResult;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogTransferDocument;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierSemanticRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientReadinessResponse;
import br.com.contadoresassociados.folhas.contracts.clients.ClientSearchResponse;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.clients.SignatureModeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.Client;
import br.com.contadoresassociados.folhas.domain.clients.ClientCatalogDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierSemanticRole;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierType;
import br.com.contadoresassociados.folhas.domain.clients.ClientOperationalReadiness;
import br.com.contadoresassociados.folhas.domain.clients.ClientPartnerDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientPartnerRole;
import br.com.contadoresassociados.folhas.domain.clients.DeliveryRole;
import br.com.contadoresassociados.folhas.domain.clients.EmailAddress;
import br.com.contadoresassociados.folhas.domain.clients.EstablishmentDraft;
import br.com.contadoresassociados.folhas.domain.clients.MessageTemplate;
import br.com.contadoresassociados.folhas.domain.clients.MessageTemplateDraft;
import br.com.contadoresassociados.folhas.domain.clients.PersonType;
import br.com.contadoresassociados.folhas.domain.clients.RecipientDraft;
import br.com.contadoresassociados.folhas.domain.clients.SignatureMode;
import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.CatalogRecords;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Collator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Catálogo de clientes e modelos do perfil Local, gravado na tabela {@code catalog_cache} do
 * {@code cache.db} com os mesmos tipos de registro e o mesmo JSON do .NET — o catálogo de quem já
 * usa a versão anterior abre sem conversão.
 *
 * <p>Preservado do .NET: regras de unicidade (CPF/CNPJ/raiz/estabelecimento, código contábil,
 * identificadores únicos), quarentena de identificadores fiscais incompatíveis com o tipo de
 * pessoa, tolerância a e-mails legados inválidos, arquivamento só de inativos e sem referência
 * operacional, importação com simulação, auditoria local.
 *
 * <p>Melhorias: cadastro + revalidação da revisão numa única transação SQLite (o .NET dependia
 * do rastreamento do EF); busca sem acento e com CNPJ alfanumérico (1.1); paginação real
 * ({@code skip/take}); {@code getMany} em lote (6.13); data contábil de Brasília na prontidão
 * (3.7); placeholders validados ao salvar modelo (8.15); erros com código estável
 * ({@link CatalogException}).
 */
public final class SqliteLocalClientCatalogService implements ClientCatalogService {

    public static final int CURRENT_TRANSFER_FORMAT_VERSION = 1;
    public static final String AUDIT_RECORD_TYPE = "local-catalog-audit";
    public static final String ARCHIVED_CLIENT_RECORD_TYPE = "local-client-archived";
    public static final String ARCHIVED_TEMPLATE_RECORD_TYPE = "local-message-template-archived";
    public static final String CLIENT_RECORD_TYPE = "local-client";
    public static final String TEMPLATE_RECORD_TYPE = "local-message-template";
    static final UUID LOCAL_ACTOR_ID = UUID.fromString("9f54a0f0-93a1-4a7c-936d-f88bca95f771");
    static final UUID LOCAL_ORGANIZATION_ID = UUID.fromString("941a50c9-2e1a-4f22-9f32-70e25e2e481d");
    static final int MAX_PAGE = 200;

    /** Revalida a revisão de documentos na mesma transação da alteração cadastral. */
    @FunctionalInterface
    public interface ReviewRevalidation {
        void revalidate();
    }

    private final LocalDatabase database;
    private final Clock clock;
    private final ReviewRevalidation revalidation;
    private final Collator collator;

    public SqliteLocalClientCatalogService(LocalDatabase database, Clock clock, ReviewRevalidation revalidation) {
        this.database = database;
        this.clock = clock;
        this.revalidation = revalidation;
        this.collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        this.collator.setStrength(Collator.SECONDARY);
    }

    // ================================================================== consultas

    @Override
    public ClientSearchResponse search(String search, Boolean active, PersonTypeModel personType, int skip, int take) {
        var clients = database.read(this::loadClients);
        Stream<ClientDetails> query = clients.stream();
        if (active != null) {
            query = query.filter(c -> c.isActive() == active);
        }
        if (personType != null) {
            query = query.filter(c -> c.personType() == personType);
        }
        if (search != null && !search.isBlank()) {
            var term = ClientMatcher.normalizeName(search);
            var tax = taxDigits(search);
            query = query.filter(c -> ClientMatcher.normalizeName(c.legalNameOrFullName()).contains(term)
                    || ClientMatcher.normalizeName(c.preferredName()).contains(term)
                    || (c.internalCode() != null && c.internalCode().toUpperCase(Locale.ROOT)
                            .contains(search.strip().toUpperCase(Locale.ROOT)))
                    || (!tax.isEmpty() && c.primaryTaxId() != null && c.primaryTaxId().contains(tax)));
        }
        var filtered = query.sorted(Comparator.comparing(ClientDetails::isActive).reversed()
                .thenComparing(CatalogMapping::displayName, collator)).toList();
        var safeTake = take <= 0 ? MAX_PAGE : Math.min(take, MAX_PAGE);
        var safeSkip = Math.max(0, skip);
        var page = filtered.stream().skip(safeSkip).limit(safeTake).map(CatalogMapping::toListItem)
                .toList();
        return new ClientSearchResponse(page, filtered.size(), safeSkip, safeTake);
    }

    @Override
    public Optional<ClientDetails> get(UUID clientId) {
        return database.read(c -> CatalogRecords.find(c, CLIENT_RECORD_TYPE, clientId, ClientDetails.class));
    }

    @Override
    public List<ClientDetails> getMany(List<UUID> clientIds) {
        var wanted = new HashSet<>(clientIds);
        var byId = database.read(this::loadClients).stream().filter(c -> wanted.contains(c.id()))
                .collect(Collectors.toMap(ClientDetails::id, c -> c));
        return clientIds.stream().distinct().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Override
    public Optional<ClientReadinessResponse> readiness(UUID clientId) {
        var stored = get(clientId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        var client = stored.get();
        var today = clock.accountingDate();
        var legacy = CatalogRules.legacyEmailReadinessBlocks(client, today);
        if (!legacy.isEmpty()) {
            return Optional.of(new ClientReadinessResponse(client.id(), false, legacy));
        }
        var compatible = CatalogRules.removeInactiveLegacyInvalidEmails(client);
        var domain = validateDomain(() -> new Client(client.id(), LOCAL_ORGANIZATION_ID, CatalogMapping.toDomain(compatible),
                LOCAL_ACTOR_ID, client.updatedAtUtc().toInstant()));
        var readiness = ClientOperationalReadiness.evaluate(domain, today);
        return Optional.of(new ClientReadinessResponse(client.id(), readiness.eligible(), readiness.blockCodes()));
    }

    @Override
    public List<MessageTemplateModel> templates(UUID clientId, boolean includeInactive) {
        return database.read(this::loadTemplates).stream()
                .filter(t -> (clientId == null || t.clientId() == null || t.clientId().equals(clientId))
                        && (includeInactive || t.isActive()))
                .sorted(Comparator.comparing(MessageTemplateModel::name, collator)).toList();
    }

    @Override
    public List<AuditEventModel> audit(UUID clientId) {
        var id = clientId.toString();
        return database.read(c -> CatalogRecords.latest(c, AUDIT_RECORD_TYPE, 1000, AuditEventModel.class)).stream()
                .filter(a -> "client".equals(a.entityType()) && id.equalsIgnoreCase(a.entityId()))
                .sorted(Comparator.comparing(AuditEventModel::timestampUtc).reversed()).limit(100).toList();
    }

    // ================================================================== clientes

    @Override
    public ClientDetails save(UUID clientId, ClientMutationRequest request) {
        Objects.requireNonNull(request, "request");
        return database.transaction(c -> {
            var existing = clientId == null ? null
                    : CatalogRecords.find(c, CLIENT_RECORD_TYPE, clientId, ClientDetails.class).orElse(null);
            if (clientId == null && request.expectedVersion() != 0) {
                throw CatalogRules.invalid("Um novo cliente deve usar a versão esperada zero.");
            }
            if (clientId != null && existing == null) {
                throw new CatalogException.NotFound("O cliente que seria alterado");
            }
            if (existing != null && request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(clientId, request.expectedVersion(), existing.version());
            }
            var clients = loadClients(c);
            CatalogRules.validateTemplateReferences(request.defaultSubjectTemplateId(), request.defaultBodyTemplateId(),
                    loadTemplates(c), null);
            if (existing != null && existing.personType() != request.personType()) {
                throw CatalogRules.invalid("O tipo do cliente não pode ser alterado depois do cadastro. Arquive-o e crie outro "
                        + "cadastro se a identidade estiver incorreta.");
            }
            var now = now();
            var normalizedExisting = existing == null ? null
                    : CatalogRules.quarantineMisplacedTaxIdentifiers(CatalogRules.prepareLegacyEmailDataForMutation(existing, request));
            var normalizedRequest = existing == null ? request
                    : CatalogRules.quarantineLegacyMisplacedTaxIdentifiers(existing,
                            CatalogRules.prepareLegacyEmailRequestForMutation(existing, request));
            var domain = validateDomain(() -> normalizeClient(normalizedExisting, normalizedRequest, now));
            var mapped = CatalogMapping.map(domain);
            var saved = CatalogMapping.withMeta(mapped, existing == null ? 1 : existing.version() + 1,
                    existing == null ? now : existing.createdAtUtc(), now, mapped.isActive());
            CatalogRules.validateClientUniqueness(saved, clients.stream().filter(o -> !o.id().equals(saved.id())).toList());
            CatalogRecords.upsert(c, CLIENT_RECORD_TYPE, saved.id(), saved.version(), saved.updatedAtUtc(), saved);
            addAudit(c, "client", saved.id(), clientAuditAction(existing, saved), saved.version());
            revalidateReview();
            return saved;
        });
    }

    @Override
    public ClientDetails setClientActive(UUID clientId, long expectedVersion, boolean active) {
        return database.transaction(c -> {
            var existing = requireClient(c, clientId);
            ensureExpectedVersion(clientId, expectedVersion, existing.version());
            if (existing.isActive() == active) {
                return existing;
            }
            var now = now();
            var updated = CatalogMapping.withMeta(CatalogRules.quarantineMisplacedTaxIdentifiers(existing), existing.version() + 1,
                    existing.createdAtUtc(), now, active);
            if (active) {
                // Registros legados podem ter e-mail que a validação atual recusa: reabrem para
                // correção e continuam bloqueados pela prontidão até serem salvos.
                CatalogRules.validateClientUniqueness(updated, loadClients(c).stream().filter(o -> !o.id().equals(clientId)).toList());
            }
            CatalogRecords.upsert(c, CLIENT_RECORD_TYPE, updated.id(), updated.version(), updated.updatedAtUtc(), updated);
            addAudit(c, "client", updated.id(), active ? "reactivated" : "deactivated", updated.version());
            revalidateReview();
            return updated;
        });
    }

    @Override
    public void archiveClient(UUID clientId, ArchiveRequest request) {
        database.transaction(c -> {
            var existing = requireClient(c, clientId);
            ensureExpectedVersion(clientId, request.expectedVersion(), existing.version());
            if (existing.isActive()) {
                throw CatalogRules.invalid("Inative o cliente antes de excluí-lo da lista. Assim nenhuma operação em andamento "
                        + "perde a referência.");
            }
            ensureClientHasNoOperationalReferences(c, clientId);
            var archivedAt = now();
            var archived = CatalogMapping.withMeta(existing, existing.version() + 1, existing.createdAtUtc(), archivedAt,
                    existing.isActive());
            moveToArchive(c, CLIENT_RECORD_TYPE, ARCHIVED_CLIENT_RECORD_TYPE, archived.id(), archived.version(),
                    archivedAt, archived);
            addAudit(c, "client", archived.id(), "archived", archived.version());
            return null;
        });
    }

    // ================================================================== modelos

    @Override
    public MessageTemplateModel saveTemplate(UUID templateId, MessageTemplateMutationRequest request) {
        Objects.requireNonNull(request, "request");
        return database.transaction(c -> {
            var templates = loadTemplates(c);
            var existing = templateId == null ? null
                    : templates.stream().filter(t -> t.id().equals(templateId)).findFirst().orElse(null);
            if (templateId == null && request.expectedVersion() != 0) {
                throw CatalogRules.invalid("Um novo modelo deve usar a versão esperada zero.");
            }
            if (templateId != null && existing == null) {
                throw new CatalogException.NotFound("O modelo que seria alterado");
            }
            if (existing != null && request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(templateId, request.expectedVersion(), existing.version());
            }
            if (request.clientId() != null && !CatalogRecords.exists(c, CLIENT_RECORD_TYPE, request.clientId())) {
                throw new CatalogException.NotFound("O cliente associado ao modelo");
            }
            if (!request.isActive()) {
                ensureTemplateIsNotReferencedAsDefault(c, templateId);
            }
            CatalogRules.validateTemplateUniqueness(templateId, request, templates);
            var now = now();
            var id = templateId == null ? UUID.randomUUID() : templateId;
            var domain = validateDomain(() -> new MessageTemplate(id, LOCAL_ORGANIZATION_ID, CatalogMapping.toDomain(request),
                    LOCAL_ACTOR_ID, now.toInstant(), TemplatePlaceholderValidator.INSTANCE));
            var saved = CatalogMapping.withMeta(CatalogMapping.map(domain), existing == null ? 1 : existing.version() + 1, now);
            CatalogRecords.upsert(c, TEMPLATE_RECORD_TYPE, saved.id(), saved.version(), saved.updatedAtUtc(), saved);
            addAudit(c, "message-template", saved.id(), existing == null ? "created" : "updated", saved.version());
            return saved;
        });
    }

    @Override
    public MessageTemplateModel setTemplateActive(UUID templateId, long expectedVersion, boolean active) {
        return database.transaction(c -> {
            var templates = loadTemplates(c);
            var existing = templates.stream().filter(t -> t.id().equals(templateId)).findFirst()
                    .orElseThrow(() -> new CatalogException.NotFound("O modelo de mensagem"));
            ensureExpectedVersion(templateId, expectedVersion, existing.version());
            if (existing.isActive() == active) {
                return existing;
            }
            if (!active) {
                ensureTemplateIsNotReferencedAsDefault(c, templateId);
            }
            var request = CatalogMapping.toMutation(existing, active);
            CatalogRules.validateTemplateUniqueness(templateId, request, templates);
            var now = now();
            // reativar/inativar não reescreve o texto: o validador de placeholders não é reaplicado
            var domain = validateDomain(() -> new MessageTemplate(existing.id(), LOCAL_ORGANIZATION_ID,
                    CatalogMapping.toDomain(request), LOCAL_ACTOR_ID, now.toInstant(), value -> { }));
            var updated = CatalogMapping.withMeta(CatalogMapping.map(domain), existing.version() + 1, now);
            CatalogRecords.upsert(c, TEMPLATE_RECORD_TYPE, updated.id(), updated.version(), updated.updatedAtUtc(),
                    updated);
            addAudit(c, "message-template", updated.id(), active ? "reactivated" : "deactivated", updated.version());
            return updated;
        });
    }

    @Override
    public void archiveTemplate(UUID templateId, ArchiveRequest request) {
        database.transaction(c -> {
            var existing = loadTemplates(c).stream().filter(t -> t.id().equals(templateId)).findFirst()
                    .orElseThrow(() -> new CatalogException.NotFound("O modelo de mensagem"));
            ensureExpectedVersion(templateId, request.expectedVersion(), existing.version());
            if (existing.isActive()) {
                throw CatalogRules.invalid("Inative a mensagem antes de excluí-la da lista.");
            }
            ensureTemplateIsNotReferencedAsDefault(c, templateId);
            var archivedAt = now();
            var archived = CatalogMapping.withMeta(existing, existing.version() + 1, archivedAt);
            moveToArchive(c, TEMPLATE_RECORD_TYPE, ARCHIVED_TEMPLATE_RECORD_TYPE, archived.id(), archived.version(),
                    archivedAt, archived);
            addAudit(c, "message-template", archived.id(), "archived", archived.version());
            return null;
        });
    }

    // ================================================================== exportar / importar

    @Override
    public ClientCatalogTransferDocument export() {
        return database.read(c -> new ClientCatalogTransferDocument(CURRENT_TRANSFER_FORMAT_VERSION, now(),
                loadClients(c).stream().sorted(Comparator.comparing(ClientDetails::legalNameOrFullName, collator))
                        .toList(),
                loadTemplates(c).stream().sorted(Comparator.comparing(MessageTemplateModel::name, collator)).toList()));
    }

    @Override
    public ClientCatalogImportResult importCatalog(ClientCatalogImportRequest request) {
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
        return database.transaction(c -> {
            var existingClients = loadClients(c).stream().collect(Collectors.toMap(ClientDetails::id, x -> x));
            var existingTemplates = loadTemplates(c).stream()
                    .collect(Collectors.toMap(MessageTemplateModel::id, x -> x));
            var workingClients = new LinkedHashMap<>(existingClients);
            var workingTemplates = new LinkedHashMap<>(existingTemplates);
            var changedClients = new ArrayList<ClientDetails>();
            var changedTemplates = new ArrayList<MessageTemplateModel>();
            var warnings = new ArrayList<String>();
            int clientsCreated = 0;
            int clientsUpdated = 0;
            int templatesCreated = 0;
            int templatesUpdated = 0;
            var now = now();
            for (var imported : document.clients()) {
                var existing = existingClients.get(imported.id());
                if (existing != null && !request.overwriteExisting()) {
                    warnings.add("O cliente " + imported.id() + " já existe e foi ignorado.");
                    continue;
                }
                var domain = validateDomain(() -> new Client(imported.id(), LOCAL_ORGANIZATION_ID, CatalogMapping.toDomain(imported),
                        LOCAL_ACTOR_ID, now.toInstant()));
                var mapped = CatalogMapping.map(domain);
                var normalized = CatalogMapping.withMeta(mapped, existing == null ? 1 : existing.version() + 1,
                        existing == null ? now : existing.createdAtUtc(), now, mapped.isActive());
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
                if (existing != null && !request.overwriteExisting()) {
                    warnings.add("O modelo " + imported.id() + " já existe e foi ignorado.");
                    continue;
                }
                var domain = validateDomain(() -> new MessageTemplate(imported.id(), LOCAL_ORGANIZATION_ID,
                        CatalogMapping.toDomain(imported), LOCAL_ACTOR_ID, now.toInstant(), TemplatePlaceholderValidator.INSTANCE));
                var normalized = CatalogMapping.withMeta(CatalogMapping.map(domain), existing == null ? 1 : existing.version() + 1, now);
                workingTemplates.put(normalized.id(), normalized);
                changedTemplates.add(normalized);
                if (existing == null) {
                    templatesCreated++;
                } else {
                    templatesUpdated++;
                }
            }
            CatalogRules.validateImportedCatalog(List.copyOf(workingClients.values()), List.copyOf(workingTemplates.values()));
            if (!request.dryRun()) {
                for (var client : changedClients) {
                    CatalogRecords.upsert(c, CLIENT_RECORD_TYPE, client.id(), client.version(), client.updatedAtUtc(),
                            client);
                    addAudit(c, "client", client.id(), "imported", client.version());
                }
                for (var template : changedTemplates) {
                    CatalogRecords.upsert(c, TEMPLATE_RECORD_TYPE, template.id(), template.version(),
                            template.updatedAtUtc(), template);
                    addAudit(c, "message-template", template.id(), "imported", template.version());
                }
                if (!changedClients.isEmpty()) {
                    revalidateReview();
                }
            }
            return new ClientCatalogImportResult(request.dryRun(), clientsCreated, clientsUpdated, templatesCreated,
                    templatesUpdated, warnings);
        });
    }

    // ================================================================== persistência

    private List<ClientDetails> loadClients(Connection c) throws SQLException {
        return CatalogRecords.all(c, CLIENT_RECORD_TYPE, ClientDetails.class);
    }

    private List<MessageTemplateModel> loadTemplates(Connection c) throws SQLException {
        return CatalogRecords.all(c, TEMPLATE_RECORD_TYPE, MessageTemplateModel.class);
    }

    private static ClientDetails requireClient(Connection c, UUID id) throws SQLException {
        return CatalogRecords.find(c, CLIENT_RECORD_TYPE, id, ClientDetails.class)
                .orElseThrow(() -> new CatalogException.NotFound("O cliente"));
    }

    private static void moveToArchive(Connection c, String current, String archived, UUID id, long version,
            OffsetDateTime at, Object value) throws SQLException {
        if (CatalogRecords.exists(c, archived, id)) {
            throw CatalogRules.invalid("Já existe uma cópia arquivada deste registro. Nenhum dado foi alterado.");
        }
        if (!CatalogRecords.delete(c, current, id)) {
            throw new CatalogException.NotFound("O registro que seria arquivado");
        }
        CatalogRecords.upsert(c, archived, id, version, at, value);
    }

    private void ensureClientHasNoOperationalReferences(Connection c, UUID clientId) throws SQLException {
        if (loadTemplates(c).stream().anyMatch(t -> clientId.equals(t.clientId()))) {
            throw CatalogRules.invalid("Este cliente possui mensagens personalizadas. Exclua primeiro essas mensagens inativas.");
        }
        if (CatalogRecords.anyGroupForClient(c, clientId)) {
            throw CatalogRules.invalid("Este cliente aparece em uma revisão de documentos. Retire os documentos relacionados antes "
                    + "de excluir o cadastro.");
        }
        for (var payload : CatalogRecords.rawPayloads(c, "document_reviews")) {
            if (clientId.equals(readReference(payload, ReviewDocument.class, "Uma revisão antiga").clientId())) {
                throw CatalogRules.invalid("Este cliente aparece em um documento importado. Retire o documento relacionado antes de "
                        + "excluir o cadastro.");
            }
        }
        for (var payload : CatalogRecords.rawPayloads(c, "dispatch_items")) {
            if (clientId.equals(readReference(payload, DispatchItem.class, "Uma mensagem antiga").clientId())) {
                throw CatalogRules.invalid("Este cliente aparece em uma mensagem preparada ou concluída e precisa permanecer no "
                        + "histórico.");
            }
        }
    }

    private static <T> T readReference(String payload, Class<T> type, String what) {
        try {
            var value = Json.read(payload, type);
            if (value == null) {
                throw new IllegalStateException("vazio");
            }
            return value;
        } catch (RuntimeException e) {
            throw CatalogRules.invalid(what + " não pôde ser conferida. O cliente não foi excluído; peça ao suporte para revisar "
                    + "o catálogo.");
        }
    }

    private void ensureTemplateIsNotReferencedAsDefault(Connection c, UUID templateId) throws SQLException {
        if (templateId == null) {
            return;
        }
        if (loadClients(c).stream().anyMatch(cl -> templateId.equals(cl.defaultSubjectTemplateId())
                || templateId.equals(cl.defaultBodyTemplateId()))) {
            throw CatalogRules.invalid("Esta mensagem está definida como padrão em um cliente. Escolha outro padrão antes de "
                    + "inativar ou excluir.");
        }
    }

    private void addAudit(Connection c, String entityType, UUID entityId, String action, long version)
            throws SQLException {
        var timestamp = now();
        var data = new LinkedHashMap<String, Object>();
        data.put("version", version);
        data.put("storage", "local");
        var audit = new AuditEventModel(UUID.randomUUID(), entityType, entityId.toString(), action, "registration",
                "information", Json.write(data), timestamp, UUID.randomUUID());
        CatalogRecords.upsert(c, AUDIT_RECORD_TYPE, audit.id(), 1, timestamp, audit);
    }

    private void revalidateReview() {
        if (revalidation != null) {
            revalidation.revalidate();
        }
    }

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }

    // ================================================================== mapeamentos

    private static Client normalizeClient(ClientDetails existing, ClientMutationRequest request, OffsetDateTime now) {
        if (existing == null) {
            return new Client(UUID.randomUUID(), LOCAL_ORGANIZATION_ID, CatalogMapping.toDomain(request), LOCAL_ACTOR_ID,
                    now.toInstant());
        }
        var client = new Client(existing.id(), LOCAL_ORGANIZATION_ID, CatalogMapping.toDomain(existing), LOCAL_ACTOR_ID,
                existing.createdAtUtc() == null ? now.toInstant() : existing.createdAtUtc().toInstant());
        client.apply(CatalogMapping.toDomain(request), client.version(), LOCAL_ACTOR_ID, now.toInstant());
        return client;
    }

    private static String clientAuditAction(ClientDetails existing, ClientDetails saved) {
        if (existing == null) {
            return "created";
        }
        if (existing.isActive() && !saved.isActive()) {
            return "deactivated";
        }
        if (!existing.isActive() && saved.isActive()) {
            return "reactivated";
        }
        return "updated";
    }



    // ================================================================== auxiliares






    private static void ensureExpectedVersion(UUID id, long expected, long actual) {
        if (expected != actual) {
            throw new CatalogException.Concurrency(id, expected, actual);
        }
    }

    private static <T> T validateDomain(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DomainValidationException e) {
            throw new CatalogException(e.code(), e.getMessage());
        } catch (ConcurrencyConflictException e) {
            throw new CatalogException("catalog.concurrency", e.getMessage());
        }
    }


    /** Dígitos e letras do termo (para buscar CNPJ alfanumérico formatado ou não). */
    static String taxDigits(String value) {
        var sb = new StringBuilder();
        for (var ch : value.toCharArray()) {
            if (Character.isDigit(ch) || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                sb.append(Character.toUpperCase(ch));
            }
        }
        var s = sb.toString();
        // termos só com letras são nomes, não identificadores fiscais
        return s.chars().anyMatch(Character::isDigit) ? s : "";
    }
}
