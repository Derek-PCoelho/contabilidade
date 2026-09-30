package br.com.contadoresassociados.folhas.infrastructure.clients;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportResult;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogTransferDocument;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientReadinessResponse;
import br.com.contadoresassociados.folhas.contracts.clients.ClientSearchResponse;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.CatalogRecords;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient.JsonResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catálogo do perfil Conectado (API central), com cache de leitura no {@code cache.db} nos mesmos
 * tipos de registro do .NET ({@code client}, {@code message-template}).
 *
 * <p>Correções: 6.8 — cada status vira um código próprio (401 reautenticar, 403 permissão/MFA,
 * 409 conflito, 429 aguardar, 5xx/rede sem conexão) em vez de "sem conexão" para tudo; 4.17 —
 * a mensagem mostrada vem do código, nunca do texto em inglês do servidor; 4.19/6.12 —
 * ativar/inativar e arquivar também no Conectado; 6.13 — {@code getMany} em lote com recuo.
 */
public final class HttpClientCatalogService implements ClientCatalogService {

    static final String CLIENT_CACHE = "client";
    static final String TEMPLATE_CACHE = "message-template";

    private final CentralApiClient api;
    private final LocalDatabase cache;

    public HttpClientCatalogService(CentralApiClient api, LocalDatabase cache) {
        this.api = api;
        this.cache = cache;
    }

    @Override
    public ClientSearchResponse search(String search, Boolean active, PersonTypeModel personType, int skip, int take) {
        var query = new ArrayList<String>();
        query.add("skip=" + Math.max(0, skip));
        query.add("take=" + (take <= 0 ? 200 : Math.min(take, 200)));
        if (search != null && !search.isBlank()) {
            query.add("search=" + encode(search.strip()));
        }
        if (active != null) {
            query.add("isActive=" + active);
        }
        if (personType != null) {
            query.add("personType=" + pascal(personType));
        }
        return read(httpGet("api/clients?" + String.join("&", query)), ClientSearchResponse.class);
    }

    @Override
    public Optional<ClientDetails> get(UUID clientId) {
        try {
            var response = httpGet("api/clients/" + clientId);
            if (response.status() == 404) {
                return Optional.empty();
            }
            var client = read(response, ClientDetails.class);
            store(CLIENT_CACHE, client.id(), client.version(), client.updatedAtUtc(), client);
            return Optional.of(client);
        } catch (CatalogException e) {
            if (!"catalog.offline".equals(e.code())) {
                throw e;
            }
            return cache.read(c -> CatalogRecords.find(c, CLIENT_CACHE, clientId, ClientDetails.class));
        }
    }

    @Override
    public List<ClientDetails> getMany(List<UUID> clientIds) {
        var ids = clientIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            var response = httpPost("api/clients/batch", Map.of("clientIds", ids));
            if (response.status() == 404 || response.status() == 405) {
                return ids.stream().map(this::get).flatMap(Optional::stream).toList();
            }
            ensure(response);
            var clients = readList(response, new TypeReference<List<ClientDetails>>() { });
            for (var client : clients) {
                store(CLIENT_CACHE, client.id(), client.version(), client.updatedAtUtc(), client);
            }
            var byId = clients.stream().collect(Collectors.toMap(ClientDetails::id, c -> c, (a, b) -> a));
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        } catch (CatalogException e) {
            if (!"catalog.offline".equals(e.code())) {
                throw e;
            }
            return cache.read(c -> {
                var list = new ArrayList<ClientDetails>();
                for (var id : ids) {
                    CatalogRecords.find(c, CLIENT_CACHE, id, ClientDetails.class).ifPresent(list::add);
                }
                return list;
            });
        }
    }

    @Override
    public ClientDetails save(UUID clientId, ClientMutationRequest request) {
        var response = clientId == null ? httpPost("api/clients", request)
                : send("PUT", "api/clients/" + clientId, request);
        var client = read(response, ClientDetails.class);
        store(CLIENT_CACHE, client.id(), client.version(), client.updatedAtUtc(), client);
        return client;
    }

    @Override
    public Optional<ClientReadinessResponse> readiness(UUID clientId) {
        var response = httpGet("api/clients/" + clientId + "/readiness");
        if (response.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(read(response, ClientReadinessResponse.class));
    }

    @Override
    public List<MessageTemplateModel> templates(UUID clientId, boolean includeInactive) {
        var url = "api/message-templates?includeInactive=" + includeInactive + (clientId == null ? ""
                : "&clientId=" + clientId);
        try {
            var response = httpGet(url);
            ensure(response);
            var templates = readList(response, new TypeReference<List<MessageTemplateModel>>() { });
            for (var t : templates) {
                store(TEMPLATE_CACHE, t.id(), t.version(), t.updatedAtUtc(), t);
            }
            return templates;
        } catch (CatalogException e) {
            if (!"catalog.offline".equals(e.code())) {
                throw e;
            }
            return cache.read(c -> CatalogRecords.all(c, TEMPLATE_CACHE, MessageTemplateModel.class)).stream()
                    .filter(t -> (clientId == null || t.clientId() == null || t.clientId().equals(clientId))
                            && (includeInactive || t.isActive()))
                    .toList();
        }
    }

    @Override
    public MessageTemplateModel saveTemplate(UUID templateId, MessageTemplateMutationRequest request) {
        var response = templateId == null ? httpPost("api/message-templates", request)
                : send("PUT", "api/message-templates/" + templateId, request);
        var template = read(response, MessageTemplateModel.class);
        store(TEMPLATE_CACHE, template.id(), template.version(), template.updatedAtUtc(), template);
        return template;
    }

    @Override
    public ClientDetails setClientActive(UUID clientId, long expectedVersion, boolean active) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", expectedVersion);
        body.put("isActive", active);
        var client = read(httpPost("api/clients/" + clientId + "/status", body), ClientDetails.class);
        store(CLIENT_CACHE, client.id(), client.version(), client.updatedAtUtc(), client);
        return client;
    }

    @Override
    public MessageTemplateModel setTemplateActive(UUID templateId, long expectedVersion, boolean active) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", expectedVersion);
        body.put("isActive", active);
        var template = read(httpPost("api/message-templates/" + templateId + "/status", body),
                MessageTemplateModel.class);
        store(TEMPLATE_CACHE, template.id(), template.version(), template.updatedAtUtc(), template);
        return template;
    }

    @Override
    public void archiveClient(UUID clientId, ArchiveRequest request) {
        ensure(httpPost("api/clients/" + clientId + "/archive", request));
        cache.transaction(c -> CatalogRecords.delete(c, CLIENT_CACHE, clientId));
    }

    @Override
    public void archiveTemplate(UUID templateId, ArchiveRequest request) {
        ensure(httpPost("api/message-templates/" + templateId + "/archive", request));
        cache.transaction(c -> CatalogRecords.delete(c, TEMPLATE_CACHE, templateId));
    }

    @Override
    public ClientCatalogTransferDocument export() {
        return read(httpGet("api/clients/catalog/export"), ClientCatalogTransferDocument.class);
    }

    @Override
    public ClientCatalogImportResult importCatalog(ClientCatalogImportRequest request) {
        return read(httpPost("api/clients/catalog/import", request), ClientCatalogImportResult.class);
    }

    @Override
    public List<AuditEventModel> audit(UUID clientId) {
        var response = httpGet("api/clients/" + clientId + "/audit?take=100");
        ensure(response);
        return readList(response, new TypeReference<List<AuditEventModel>>() { });
    }

    // ------------------------------------------------------------------ auxiliares

    private JsonResponse httpGet(String path) {
        return call(() -> api.get(path));
    }

    private JsonResponse httpPost(String path, Object body) {
        return call(() -> api.postJson(path, body));
    }

    private JsonResponse httpPut(String path, Object body) {
        return call(() -> api.putJson(path, body));
    }

    /** Falha de rede (status 0) vira {@code catalog.offline}, permitindo o recuo para o cache. */
    private static JsonResponse call(java.util.function.Supplier<JsonResponse> request) {
        try {
            return request.get();
        } catch (CentralApiClient.CentralApiException e) {
            throw new CatalogException("catalog.offline", "O servidor não está disponível no momento.");
        }
    }

    private JsonResponse send(String method, String path, Object body) {
        if ("PUT".equals(method)) {
            return httpPut(path, body);
        }
        throw new IllegalArgumentException(method);
    }

    private void store(String type, UUID id, long version, java.time.OffsetDateTime updatedAt, Object value) {
        if (cache == null) {
            return;
        }
        try {
            cache.transaction(c -> {
                CatalogRecords.upsert(c, type, id, version,
                        updatedAt == null ? java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC) : updatedAt, value);
                return null;
            });
        } catch (RuntimeException ignored) {
            // cache é só otimização de leitura offline; falha nunca bloqueia a operação no servidor
        }
    }

    private static <T> T read(JsonResponse response, Class<T> type) {
        ensure(response);
        try {
            return response.read(type);
        } catch (CentralApiClient.CentralApiException e) {
            throw new CatalogException("catalog.invalid_response", "O servidor respondeu de forma inesperada.");
        }
    }

    private static <T> T readList(JsonResponse response, TypeReference<T> type) {
        try {
            return Json.mapper().readValue(response.body(), type);
        } catch (java.io.IOException | RuntimeException e) {
            throw new CatalogException("catalog.invalid_response", "O servidor respondeu de forma inesperada.");
        }
    }

    /** Pendência 6.8/4.17: status HTTP → código estável com mensagem em português. */
    static void ensure(JsonResponse response) {
        if (response.ok()) {
            return;
        }
        throw switch (response.status()) {
            case 400, 422 -> new CatalogException("catalog.invalid", validationMessage(response));
            case 401 -> new CatalogException("catalog.authentication_required",
                    "Sua sessão expirou. Entre novamente para continuar.");
            case 403 -> new CatalogException("catalog.forbidden",
                    "Você não tem permissão para esta operação ou precisa confirmar com o segundo fator.");
            case 404 -> new CatalogException.NotFound("O registro");
            case 409 -> new CatalogException("catalog.concurrency",
                    "O cadastro foi alterado por outra pessoa. Recarregue antes de salvar.");
            case 429 -> new CatalogException("catalog.throttled",
                    "Muitas operações seguidas. Aguarde alguns segundos e tente de novo.");
            default -> new CatalogException("catalog.offline", "O servidor não está disponível no momento.");
        };
    }

    /** Usa o código do ProblemDetails quando houver; o texto livre do servidor não é exibido. */
    private static String validationMessage(JsonResponse response) {
        try {
            var node = Json.mapper().readTree(response.body());
            var code = node.path("code").asText("");
            if (!code.isBlank()) {
                return "O servidor recusou os dados (" + code + "). Revise os campos destacados.";
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // sem corpo legível
        }
        return "O servidor recusou os dados. Revise os campos destacados.";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String pascal(Enum<?> value) {
        var sb = new StringBuilder();
        for (var part : value.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
