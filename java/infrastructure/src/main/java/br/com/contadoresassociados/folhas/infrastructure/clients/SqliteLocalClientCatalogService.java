package br.com.contadoresassociados.folhas.infrastructure.clients;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
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
                .thenComparing(c -> displayName(c), collator)).toList();
        var safeTake = take <= 0 ? MAX_PAGE : Math.min(take, MAX_PAGE);
        var safeSkip = Math.max(0, skip);
        var page = filtered.stream().skip(safeSkip).limit(safeTake).map(SqliteLocalClientCatalogService::toListItem)
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
        var legacy = legacyEmailReadinessBlocks(client, today);
        if (!legacy.isEmpty()) {
            return Optional.of(new ClientReadinessResponse(client.id(), false, legacy));
        }
        var compatible = removeInactiveLegacyInvalidEmails(client);
        var domain = validateDomain(() -> new Client(client.id(), LOCAL_ORGANIZATION_ID, toDomain(compatible),
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
                throw invalid("Um novo cliente deve usar a versão esperada zero.");
            }
            if (clientId != null && existing == null) {
                throw new CatalogException.NotFound("O cliente que seria alterado");
            }
            if (existing != null && request.expectedVersion() != existing.version()) {
                throw new CatalogException.Concurrency(clientId, request.expectedVersion(), existing.version());
            }
            var clients = loadClients(c);
            validateTemplateReferences(request.defaultSubjectTemplateId(), request.defaultBodyTemplateId(),
                    loadTemplates(c), null);
            if (existing != null && existing.personType() != request.personType()) {
                throw invalid("O tipo do cliente não pode ser alterado depois do cadastro. Arquive-o e crie outro "
                        + "cadastro se a identidade estiver incorreta.");
            }
            var now = now();
            var normalizedExisting = existing == null ? null
                    : quarantineMisplacedTaxIdentifiers(prepareLegacyEmailDataForMutation(existing, request));
            var normalizedRequest = existing == null ? request
                    : quarantineLegacyMisplacedTaxIdentifiers(existing,
                            prepareLegacyEmailRequestForMutation(existing, request));
            var domain = validateDomain(() -> normalizeClient(normalizedExisting, normalizedRequest, now));
            var mapped = map(domain);
            var saved = withMeta(mapped, existing == null ? 1 : existing.version() + 1,
                    existing == null ? now : existing.createdAtUtc(), now, mapped.isActive());
            validateClientUniqueness(saved, clients.stream().filter(o -> !o.id().equals(saved.id())).toList());
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
            var updated = withMeta(quarantineMisplacedTaxIdentifiers(existing), existing.version() + 1,
                    existing.createdAtUtc(), now, active);
            if (active) {
                // Registros legados podem ter e-mail que a validação atual recusa: reabrem para
                // correção e continuam bloqueados pela prontidão até serem salvos.
                validateClientUniqueness(updated, loadClients(c).stream().filter(o -> !o.id().equals(clientId)).toList());
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
                throw invalid("Inative o cliente antes de excluí-lo da lista. Assim nenhuma operação em andamento "
                        + "perde a referência.");
            }
            ensureClientHasNoOperationalReferences(c, clientId);
            var archivedAt = now();
            var archived = withMeta(existing, existing.version() + 1, existing.createdAtUtc(), archivedAt,
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
                throw invalid("Um novo modelo deve usar a versão esperada zero.");
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
            validateTemplateUniqueness(templateId, request, templates);
            var now = now();
            var id = templateId == null ? UUID.randomUUID() : templateId;
            var domain = validateDomain(() -> new MessageTemplate(id, LOCAL_ORGANIZATION_ID, toDomain(request),
                    LOCAL_ACTOR_ID, now.toInstant(), TemplatePlaceholderValidator.INSTANCE));
            var saved = withMeta(map(domain), existing == null ? 1 : existing.version() + 1, now);
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
            var request = toMutation(existing, active);
            validateTemplateUniqueness(templateId, request, templates);
            var now = now();
            // reativar/inativar não reescreve o texto: o validador de placeholders não é reaplicado
            var domain = validateDomain(() -> new MessageTemplate(existing.id(), LOCAL_ORGANIZATION_ID,
                    toDomain(request), LOCAL_ACTOR_ID, now.toInstant(), value -> { }));
            var updated = withMeta(map(domain), existing.version() + 1, now);
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
                throw invalid("Inative a mensagem antes de excluí-la da lista.");
            }
            ensureTemplateIsNotReferencedAsDefault(c, templateId);
            var archivedAt = now();
            var archived = withMeta(existing, existing.version() + 1, archivedAt);
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
            throw invalid("A cópia de segurança está vazia.");
        }
        if (document.formatVersion() != CURRENT_TRANSFER_FORMAT_VERSION) {
            throw invalid("A versão " + document.formatVersion() + " da cópia de segurança não é compatível.");
        }
        if (document.clients().stream().map(ClientDetails::id).distinct().count() != document.clients().size()
                || document.templates().stream().map(MessageTemplateModel::id).distinct().count()
                        != document.templates().size()) {
            throw invalid("A cópia de segurança contém identificadores repetidos.");
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
                var domain = validateDomain(() -> new Client(imported.id(), LOCAL_ORGANIZATION_ID, toDomain(imported),
                        LOCAL_ACTOR_ID, now.toInstant()));
                var mapped = map(domain);
                var normalized = withMeta(mapped, existing == null ? 1 : existing.version() + 1,
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
                        toDomain(imported), LOCAL_ACTOR_ID, now.toInstant(), TemplatePlaceholderValidator.INSTANCE));
                var normalized = withMeta(map(domain), existing == null ? 1 : existing.version() + 1, now);
                workingTemplates.put(normalized.id(), normalized);
                changedTemplates.add(normalized);
                if (existing == null) {
                    templatesCreated++;
                } else {
                    templatesUpdated++;
                }
            }
            validateImportedCatalog(List.copyOf(workingClients.values()), List.copyOf(workingTemplates.values()));
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
            throw invalid("Já existe uma cópia arquivada deste registro. Nenhum dado foi alterado.");
        }
        if (!CatalogRecords.delete(c, current, id)) {
            throw new CatalogException.NotFound("O registro que seria arquivado");
        }
        CatalogRecords.upsert(c, archived, id, version, at, value);
    }

    private void ensureClientHasNoOperationalReferences(Connection c, UUID clientId) throws SQLException {
        if (loadTemplates(c).stream().anyMatch(t -> clientId.equals(t.clientId()))) {
            throw invalid("Este cliente possui mensagens personalizadas. Exclua primeiro essas mensagens inativas.");
        }
        if (CatalogRecords.anyGroupForClient(c, clientId)) {
            throw invalid("Este cliente aparece em uma revisão de documentos. Retire os documentos relacionados antes "
                    + "de excluir o cadastro.");
        }
        for (var payload : CatalogRecords.rawPayloads(c, "document_reviews")) {
            if (clientId.equals(readReference(payload, ReviewDocument.class, "Uma revisão antiga").clientId())) {
                throw invalid("Este cliente aparece em um documento importado. Retire o documento relacionado antes de "
                        + "excluir o cadastro.");
            }
        }
        for (var payload : CatalogRecords.rawPayloads(c, "dispatch_items")) {
            if (clientId.equals(readReference(payload, DispatchItem.class, "Uma mensagem antiga").clientId())) {
                throw invalid("Este cliente aparece em uma mensagem preparada ou concluída e precisa permanecer no "
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
            throw invalid(what + " não pôde ser conferida. O cliente não foi excluído; peça ao suporte para revisar "
                    + "o catálogo.");
        }
    }

    private void ensureTemplateIsNotReferencedAsDefault(Connection c, UUID templateId) throws SQLException {
        if (templateId == null) {
            return;
        }
        if (loadClients(c).stream().anyMatch(cl -> templateId.equals(cl.defaultSubjectTemplateId())
                || templateId.equals(cl.defaultBodyTemplateId()))) {
            throw invalid("Esta mensagem está definida como padrão em um cliente. Escolha outro padrão antes de "
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

    // ================================================================== validações

    static void validateImportedCatalog(List<ClientDetails> clients, List<MessageTemplateModel> templates) {
        for (var i = 0; i < clients.size(); i++) {
            validateClientUniqueness(clients.get(i), clients.subList(i + 1, clients.size()));
        }
        var clientIds = clients.stream().map(ClientDetails::id).collect(Collectors.toSet());
        if (templates.stream().anyMatch(t -> t.clientId() != null && !clientIds.contains(t.clientId()))) {
            throw invalid("Todo modelo importado deve referenciar um cliente existente.");
        }
        var activeTemplateIds = templates.stream().filter(MessageTemplateModel::isActive).map(MessageTemplateModel::id)
                .collect(Collectors.toSet());
        for (var client : clients) {
            validateTemplateReferences(client.defaultSubjectTemplateId(), client.defaultBodyTemplateId(), templates,
                    activeTemplateIds);
        }
        var names = new HashSet<String>();
        for (var t : templates) {
            if (!names.add(t.clientId() + "|" + t.name().strip().toLowerCase(Locale.ROOT))) {
                throw invalid("A cópia criaria modelos com nomes repetidos no mesmo cadastro.");
            }
        }
        var defaults = new HashSet<String>();
        for (var t : templates) {
            if (t.isActive() && t.isDefault() && !defaults.add(t.clientId() + "|" + t.documentTypeId())) {
                throw invalid("Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
            }
        }
    }

    static void validateClientUniqueness(ClientDetails candidate, List<ClientDetails> others) {
        var otherTax = others.stream().flatMap(o -> taxIdentities(o).stream()).collect(Collectors.toSet());
        if (taxIdentities(candidate).stream().anyMatch(otherTax::contains)) {
            throw new CatalogException.Duplicate("CPF, CNPJ, raiz empresarial ou estabelecimento");
        }
        var otherCodes = others.stream().flatMap(o -> internalCodes(o).stream()).collect(Collectors.toSet());
        if (internalCodes(candidate).stream().anyMatch(otherCodes::contains)) {
            throw new CatalogException.Duplicate("código contábil");
        }
        var candidateUnique = comparable(candidate).filter(ClientIdentifierModel::isUniqueWithinOrganization)
                .map(SqliteLocalClientCatalogService::identifierKey).collect(Collectors.toSet());
        var candidateAll = comparable(candidate).map(SqliteLocalClientCatalogService::identifierKey)
                .collect(Collectors.toSet());
        var otherUnique = others.stream().flatMap(SqliteLocalClientCatalogService::comparable)
                .filter(ClientIdentifierModel::isUniqueWithinOrganization)
                .map(SqliteLocalClientCatalogService::identifierKey).collect(Collectors.toSet());
        var otherAll = others.stream().flatMap(SqliteLocalClientCatalogService::comparable)
                .map(SqliteLocalClientCatalogService::identifierKey).collect(Collectors.toSet());
        if (candidateUnique.stream().anyMatch(otherAll::contains) || otherUnique.stream().anyMatch(candidateAll::contains)) {
            throw new CatalogException.Duplicate("identificador");
        }
    }

    private static String identifierKey(ClientIdentifierModel identifier) {
        return identifier.type() + "|" + identifier.value();
    }

    static Set<String> taxIdentities(ClientDetails client) {
        var values = new HashSet<String>();
        var tax = client.primaryTaxId() == null ? "" : client.primaryTaxId();
        if (client.personType() == PersonTypeModel.INDIVIDUAL) {
            values.add("cpf|" + tax);
        } else {
            values.add("cnpj|" + tax);
            if (tax.length() >= BrazilianRegistration.CNPJ_ROOT_LENGTH) {
                values.add("cnpj-root|" + tax.substring(0, BrazilianRegistration.CNPJ_ROOT_LENGTH));
            }
        }
        for (var identifier : client.identifiers()) {
            var v = identifier.value() == null ? "" : identifier.value();
            switch (identifier.type()) {
                case CPF -> {
                    if (client.personType() == PersonTypeModel.INDIVIDUAL) {
                        values.add("cpf|" + v);
                    }
                }
                case CNPJ -> {
                    if (client.personType() == PersonTypeModel.LEGAL_ENTITY) {
                        values.add("cnpj|" + v);
                        if (v.length() >= 8) {
                            values.add("cnpj-root|" + v.substring(0, 8));
                        }
                    }
                }
                case CNPJ_ROOT -> {
                    if (client.personType() == PersonTypeModel.LEGAL_ENTITY) {
                        values.add("cnpj-root|" + v);
                    }
                }
                default -> {
                    // demais identificadores não são fiscais
                }
            }
        }
        for (var establishment : client.establishments()) {
            var cnpj = establishment.cnpj() == null ? "" : establishment.cnpj();
            values.add("cnpj|" + cnpj);
            if (cnpj.length() >= 8) {
                values.add("cnpj-root|" + cnpj.substring(0, 8));
            }
        }
        return values;
    }

    private static Stream<ClientIdentifierModel> comparable(ClientDetails client) {
        return client.identifiers().stream().filter(i -> !isMisplacedTaxIdentifier(client.personType(), i.type()));
    }

    static Set<String> internalCodes(ClientDetails client) {
        var values = new HashSet<String>();
        if (client.internalCode() != null && !client.internalCode().isBlank()) {
            values.add(client.internalCode().toLowerCase(Locale.ROOT));
        }
        client.identifiers().stream().filter(i -> i.type() == ClientIdentifierTypeModel.INTERNAL_CODE)
                .forEach(i -> values.add(i.value().toLowerCase(Locale.ROOT)));
        client.establishments().stream().filter(e -> e.internalCode() != null && !e.internalCode().isBlank())
                .forEach(e -> values.add(e.internalCode().toLowerCase(Locale.ROOT)));
        return values;
    }

    static void validateTemplateReferences(UUID subject, UUID body, List<MessageTemplateModel> templates,
            Set<UUID> activeIds) {
        var requested = Stream.of(subject, body).filter(id -> id != null && !id.equals(new UUID(0, 0))).distinct()
                .toList();
        if (requested.isEmpty()) {
            return;
        }
        var active = activeIds != null ? activeIds : templates.stream().filter(MessageTemplateModel::isActive)
                .map(MessageTemplateModel::id).collect(Collectors.toSet());
        if (requested.stream().anyMatch(id -> !active.contains(id))) {
            throw invalid("Os modelos padrão devem existir e estar ativos.");
        }
    }

    static void validateTemplateUniqueness(UUID templateId, MessageTemplateMutationRequest request,
            List<MessageTemplateModel> templates) {
        var others = templates.stream().filter(t -> !t.id().equals(templateId)).toList();
        var name = request.name() == null ? "" : request.name().strip();
        if (others.stream().anyMatch(t -> Objects.equals(t.clientId(), request.clientId())
                && t.name().equalsIgnoreCase(name))) {
            throw new CatalogException.Duplicate("nome de modelo neste cadastro");
        }
        if (request.isActive() && request.isDefault() && others.stream().anyMatch(t -> t.isActive() && t.isDefault()
                && Objects.equals(t.clientId(), request.clientId())
                && Objects.equals(t.documentTypeId(), request.documentTypeId()))) {
            throw invalid("Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
        }
    }

    // ================================================================== compatibilidade com dados legados

    static boolean isMisplacedTaxIdentifier(PersonTypeModel personType, ClientIdentifierTypeModel type) {
        return switch (personType) {
            case LEGAL_ENTITY -> type == ClientIdentifierTypeModel.CPF;
            case INDIVIDUAL -> type == ClientIdentifierTypeModel.CNPJ || type == ClientIdentifierTypeModel.CNPJ_ROOT;
        };
    }

    private static List<ClientIdentifierModel> quarantine(PersonTypeModel personType,
            List<ClientIdentifierModel> identifiers) {
        return identifiers.stream().map(i -> isMisplacedTaxIdentifier(personType, i.type()) && i.isActive()
                ? new ClientIdentifierModel(i.id(), i.type(), i.value(), i.semanticRole(), i.priority(), false,
                        i.isUniqueWithinOrganization())
                : i).toList();
    }

    static ClientDetails quarantineMisplacedTaxIdentifiers(ClientDetails client) {
        return copy(client, quarantine(client.personType(), client.identifiers()), client.recipients(),
                client.partners());
    }

    static ClientMutationRequest quarantineLegacyMisplacedTaxIdentifiers(ClientDetails existing,
            ClientMutationRequest request) {
        var legacyIds = existing.identifiers().stream()
                .filter(i -> isMisplacedTaxIdentifier(existing.personType(), i.type())).map(ClientIdentifierModel::id)
                .collect(Collectors.toSet());
        var identifiers = request.identifiers().stream().map(i -> legacyIds.contains(i.id())
                && isMisplacedTaxIdentifier(request.personType(), i.type()) && i.isActive()
                ? new ClientIdentifierModel(i.id(), i.type(), i.value(), i.semanticRole(), i.priority(), false,
                        i.isUniqueWithinOrganization())
                : i).toList();
        return copy(request, identifiers, request.recipients(), request.partners());
    }

    static boolean validOptionalEmail(String email) {
        return email == null || email.isBlank() || EmailAddress.isValid(email);
    }

    static ClientDetails prepareLegacyEmailDataForMutation(ClientDetails existing, ClientMutationRequest request) {
        var recipients = existing.recipients().stream().filter(r -> EmailAddress.isValid(r.email())
                || request.recipients().stream().anyMatch(q -> q.id().equals(r.id())
                        && (q.isActive() || EmailAddress.isValid(q.email()))))
                .map(r -> {
                    if (EmailAddress.isValid(r.email())) {
                        return r;
                    }
                    var replacement = request.recipients().stream().filter(q -> q.id().equals(r.id())).findFirst()
                            .orElseThrow();
                    return EmailAddress.isValid(replacement.email()) ? replacement : r;
                }).toList();
        var partners = existing.partners().stream().filter(p -> validOptionalEmail(p.email())
                || request.partners().stream().anyMatch(q -> q.id().equals(p.id())
                        && (q.isActive() || validOptionalEmail(q.email()))))
                .map(p -> {
                    if (validOptionalEmail(p.email())) {
                        return p;
                    }
                    var replacement = request.partners().stream().filter(q -> q.id().equals(p.id())).findFirst()
                            .orElseThrow();
                    return validOptionalEmail(replacement.email()) ? replacement : p;
                }).toList();
        return copy(existing, existing.identifiers(), recipients, partners);
    }

    static ClientMutationRequest prepareLegacyEmailRequestForMutation(ClientDetails existing,
            ClientMutationRequest request) {
        var invalidRecipients = existing.recipients().stream().filter(r -> !EmailAddress.isValid(r.email()))
                .map(RecipientModel::id).collect(Collectors.toSet());
        var invalidPartners = existing.partners().stream().filter(p -> !validOptionalEmail(p.email()))
                .map(ClientPartnerModel::id).collect(Collectors.toSet());
        var recipients = request.recipients().stream().filter(r -> !invalidRecipients.contains(r.id()) || r.isActive()
                || EmailAddress.isValid(r.email())).toList();
        var partners = request.partners().stream().filter(p -> !invalidPartners.contains(p.id()) || p.isActive()
                || validOptionalEmail(p.email())).toList();
        return copy(request, request.identifiers(), recipients, partners);
    }

    static ClientDetails removeInactiveLegacyInvalidEmails(ClientDetails client) {
        return copy(client, client.identifiers(),
                client.recipients().stream().filter(r -> r.isActive() || EmailAddress.isValid(r.email())).toList(),
                client.partners().stream().filter(p -> p.isActive() || validOptionalEmail(p.email())).toList());
    }

    static List<String> legacyEmailReadinessBlocks(ClientDetails client, LocalDate today) {
        var blocks = new java.util.LinkedHashSet<String>();
        if (!client.isActive()) {
            blocks.add(ClientOperationalReadiness.CLIENT_INACTIVE);
        }
        if (client.recipients().stream().anyMatch(r -> r.isActive() && !EmailAddress.isValid(r.email()))) {
            blocks.add(ClientOperationalReadiness.RECIPIENT_EMAIL_INVALID);
        }
        if (client.recipients().stream().noneMatch(r -> r.deliveryRole() == DeliveryRoleModel.TO && r.isActive()
                && (r.validFrom() == null || !r.validFrom().isAfter(today))
                && (r.validTo() == null || !r.validTo().isBefore(today)) && EmailAddress.isValid(r.email()))) {
            blocks.add(ClientOperationalReadiness.NO_ACTIVE_TO_RECIPIENT);
        }
        if (client.partners().stream().anyMatch(p -> p.isActive() && !validOptionalEmail(p.email()))) {
            blocks.add(ClientOperationalReadiness.PARTNER_EMAIL_INVALID);
        }
        return List.copyOf(blocks);
    }

    // ================================================================== mapeamentos

    private static Client normalizeClient(ClientDetails existing, ClientMutationRequest request, OffsetDateTime now) {
        if (existing == null) {
            return new Client(UUID.randomUUID(), LOCAL_ORGANIZATION_ID, toDomain(request), LOCAL_ACTOR_ID,
                    now.toInstant());
        }
        var client = new Client(existing.id(), LOCAL_ORGANIZATION_ID, toDomain(existing), LOCAL_ACTOR_ID,
                existing.createdAtUtc() == null ? now.toInstant() : existing.createdAtUtc().toInstant());
        client.apply(toDomain(request), client.version(), LOCAL_ACTOR_ID, now.toInstant());
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

    static ClientListItem toListItem(ClientDetails client) {
        String masked;
        try {
            masked = BrazilianRegistration.mask(client.primaryTaxId());
        } catch (DomainValidationException e) {
            masked = "***";
        }
        return new ClientListItem(client.id(), client.personType(), displayName(client), masked, client.internalCode(),
                client.isActive(), client.version(), (int) client.establishments().stream()
                        .filter(EstablishmentModel::isActive).count(),
                (int) client.recipients().stream().filter(RecipientModel::isActive).count(), client.updatedAtUtc());
    }

    private static String displayName(ClientDetails client) {
        return client.preferredName() != null && !client.preferredName().isBlank() ? client.preferredName()
                : client.legalNameOrFullName();
    }

    static ClientDetails map(Client client) {
        return new ClientDetails(client.id(), PersonTypeModel.valueOf(client.personType().name()),
                client.legalNameOrFullName(), client.preferredName(), client.internalCode(),
                client.primaryTaxIdNormalized(), client.active(), client.defaultSubjectTemplateId(),
                client.defaultBodyTemplateId(), client.notes(), client.version(), utc(client.createdAtUtc()),
                utc(client.updatedAtUtc()),
                client.identifiers().stream().sorted(Comparator.comparingInt(i -> i.priority()))
                        .map(i -> new ClientIdentifierModel(i.id(), ClientIdentifierTypeModel.valueOf(i.type().name()),
                                i.valueNormalized(), ClientIdentifierSemanticRoleModel.valueOf(i.semanticRole().name()),
                                i.priority(), i.active(), i.uniqueWithinOrganization())).toList(),
                client.establishments().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.Establishment e) -> !e.headOffice())
                        .thenComparing(e -> e.displayName() == null ? "" : e.displayName()))
                        .map(e -> new EstablishmentModel(e.id(), e.cnpjNormalized(), e.legalName(), e.displayName(),
                                e.internalCode(), e.headOffice(), e.active())).toList(),
                client.recipients().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.Recipient r) -> r.deliveryRole())
                        .thenComparing(r -> r.displayName() == null ? "" : r.displayName()))
                        .map(r -> new RecipientModel(r.id(), r.establishmentId(), r.displayName(), r.emailNormalized(),
                                DeliveryRoleModel.valueOf(r.deliveryRole().name()), r.documentTypeId(), r.primary(),
                                r.active(), r.validFrom(), r.validTo())).toList(),
                client.partners().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.ClientPartner p) -> p.role())
                        .thenComparing(p -> p.fullName() == null ? "" : p.fullName()))
                        .map(p -> new ClientPartnerModel(p.id(), p.fullName(), p.cpfNormalized(),
                                ClientPartnerRoleModel.valueOf(p.role().name()), p.active(), p.emailNormalized()))
                        .toList());
    }

    static MessageTemplateModel map(MessageTemplate t) {
        return new MessageTemplateModel(t.id(), t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), SignatureModeModel.valueOf(t.signatureMode().name()), t.isDefault(), t.active(),
                t.version(), utc(t.updatedAtUtc()));
    }

    static ClientCatalogDraft toDomain(ClientMutationRequest r) {
        return new ClientCatalogDraft(PersonType.valueOf(r.personType().name()), r.legalNameOrFullName(),
                r.preferredName(), r.internalCode(), r.primaryTaxId(), r.isActive(), r.defaultSubjectTemplateId(),
                r.defaultBodyTemplateId(), r.notes(), r.identifiers().stream().map(SqliteLocalClientCatalogService::toDomain)
                        .toList(),
                r.establishments().stream().map(SqliteLocalClientCatalogService::toDomain).toList(),
                r.recipients().stream().map(SqliteLocalClientCatalogService::toDomain).toList(),
                r.partners().stream().map(SqliteLocalClientCatalogService::toDomain).toList());
    }

    static ClientCatalogDraft toDomain(ClientDetails c) {
        return new ClientCatalogDraft(PersonType.valueOf(c.personType().name()), c.legalNameOrFullName(),
                c.preferredName(), c.internalCode(), c.primaryTaxId(), c.isActive(), c.defaultSubjectTemplateId(),
                c.defaultBodyTemplateId(), c.notes(), c.identifiers().stream().map(SqliteLocalClientCatalogService::toDomain)
                        .toList(),
                c.establishments().stream().map(SqliteLocalClientCatalogService::toDomain).toList(),
                c.recipients().stream().map(SqliteLocalClientCatalogService::toDomain).toList(),
                c.partners().stream().map(SqliteLocalClientCatalogService::toDomain).toList());
    }

    private static ClientIdentifierDraft toDomain(ClientIdentifierModel i) {
        return new ClientIdentifierDraft(i.id(), ClientIdentifierType.valueOf(i.type().name()), i.value(),
                ClientIdentifierSemanticRole.valueOf(i.semanticRole().name()), i.priority(), i.isActive(),
                i.isUniqueWithinOrganization());
    }

    private static EstablishmentDraft toDomain(EstablishmentModel e) {
        return new EstablishmentDraft(e.id(), e.cnpj(), e.legalName(), e.displayName(), e.internalCode(),
                e.isHeadOffice(), e.isActive());
    }

    private static RecipientDraft toDomain(RecipientModel r) {
        return new RecipientDraft(r.id(), r.establishmentId(), r.displayName(), r.email(),
                DeliveryRole.valueOf(r.deliveryRole().name()), r.documentTypeId(), r.isPrimary(), r.isActive(),
                r.validFrom(), r.validTo());
    }

    private static ClientPartnerDraft toDomain(ClientPartnerModel p) {
        return new ClientPartnerDraft(p.id(), p.fullName(), p.cpf(), ClientPartnerRole.valueOf(p.role().name()),
                p.isActive(), p.email());
    }

    private static MessageTemplateDraft toDomain(MessageTemplateMutationRequest r) {
        return new MessageTemplateDraft(r.clientId(), r.documentTypeId(), r.name(), r.subjectTemplate(),
                r.bodyTemplate(), SignatureMode.valueOf(r.signatureMode().name()), r.isDefault(), r.isActive());
    }

    private static MessageTemplateDraft toDomain(MessageTemplateModel t) {
        return new MessageTemplateDraft(t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), SignatureMode.valueOf(t.signatureMode().name()), t.isDefault(), t.isActive());
    }

    private static MessageTemplateMutationRequest toMutation(MessageTemplateModel t, boolean active) {
        return new MessageTemplateMutationRequest(t.version(), t.clientId(), t.documentTypeId(), t.name(),
                t.subjectTemplate(), t.bodyTemplate(), t.signatureMode(), t.isDefault(), active);
    }

    // ================================================================== auxiliares

    private static ClientDetails withMeta(ClientDetails c, long version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, boolean active) {
        return new ClientDetails(c.id(), c.personType(), c.legalNameOrFullName(), c.preferredName(), c.internalCode(),
                c.primaryTaxId(), active, c.defaultSubjectTemplateId(), c.defaultBodyTemplateId(), c.notes(), version,
                createdAt, updatedAt, c.identifiers(), c.establishments(), c.recipients(), c.partners());
    }

    private static MessageTemplateModel withMeta(MessageTemplateModel t, long version, OffsetDateTime updatedAt) {
        return new MessageTemplateModel(t.id(), t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), t.signatureMode(), t.isDefault(), t.isActive(), version, updatedAt);
    }

    private static ClientDetails copy(ClientDetails c, List<ClientIdentifierModel> identifiers,
            List<RecipientModel> recipients, List<ClientPartnerModel> partners) {
        return new ClientDetails(c.id(), c.personType(), c.legalNameOrFullName(), c.preferredName(), c.internalCode(),
                c.primaryTaxId(), c.isActive(), c.defaultSubjectTemplateId(), c.defaultBodyTemplateId(), c.notes(),
                c.version(), c.createdAtUtc(), c.updatedAtUtc(), identifiers, c.establishments(), recipients, partners);
    }

    private static ClientMutationRequest copy(ClientMutationRequest r, List<ClientIdentifierModel> identifiers,
            List<RecipientModel> recipients, List<ClientPartnerModel> partners) {
        return new ClientMutationRequest(r.expectedVersion(), r.personType(), r.legalNameOrFullName(),
                r.preferredName(), r.internalCode(), r.primaryTaxId(), r.isActive(), r.defaultSubjectTemplateId(),
                r.defaultBodyTemplateId(), r.notes(), identifiers, r.establishments(), recipients, partners);
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

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

    private static CatalogException invalid(String message) {
        return new CatalogException("catalog.invalid", message);
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
