package br.com.contadoresassociados.folhas.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.infrastructure.documents.HttpClientResolver;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectedCatalogTest {

    record Reply(int status, String body) {
    }

    HttpServer server;
    volatile Function<String, Reply> handler;
    final List<String> hits = new CopyOnWriteArrayList<>();
    LocalDatabase cache;
    HttpClientCatalogService catalog;
    HttpClientResolver resolver;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            var target = ex.getRequestMethod() + " " + ex.getRequestURI();
            ex.getRequestBody().readAllBytes();
            hits.add(target);
            var reply = handler.apply(target);
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        var api = new CentralApiClient(null, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new InMemorySecretStore(), Duration.ofSeconds(5), "1.0.0");
        cache = LocalDatabase.inMemory();
        catalog = new HttpClientCatalogService(api, cache);
        resolver = new HttpClientResolver(api);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        cache.close();
    }

    static String client(UUID id) {
        return "{\"id\":\"" + id + "\",\"personType\":1,\"legalNameOrFullName\":\"Cache Ltda\",\"primaryTaxId\":"
                + "\"11222333000181\",\"isActive\":true,\"version\":4,\"updatedAtUtc\":\"2026-09-01T10:00:00Z\"}";
    }

    @Test
    void getCachesAndFallsBackWhenOffline() {
        var id = UUID.randomUUID();
        handler = t -> new Reply(200, client(id));
        assertThat(catalog.get(id)).hasValueSatisfying(c -> assertThat(c.version()).isEqualTo(4));
        server.stop(0);
        assertThat(catalog.get(id)).hasValueSatisfying(c -> assertThat(c.legalNameOrFullName()).isEqualTo("Cache Ltda"));
        assertThat(catalog.getMany(List.of(id))).hasSize(1);
    }

    @Test
    void statusCodesBecomeStablePortugueseErrors() {
        handler = t -> new Reply(401, "");
        assertThatThrownBy(() -> catalog.search(null, null, null, 0, 20)).isInstanceOf(CatalogException.class)
                .extracting(e -> ((CatalogException) e).code()).isEqualTo("catalog.authentication_required");
        handler = t -> new Reply(403, "");
        assertThatThrownBy(() -> catalog.export()).extracting(e -> ((CatalogException) e).code())
                .isEqualTo("catalog.forbidden");
        handler = t -> new Reply(409, "{\"title\":\"Conflict version\"}");
        assertThatThrownBy(() -> catalog.setClientActive(UUID.randomUUID(), 1, false))
                .hasMessageContaining("alterado por outra pessoa");
        handler = t -> new Reply(429, "");
        assertThatThrownBy(() -> catalog.audit(UUID.randomUUID())).extracting(e -> ((CatalogException) e).code())
                .isEqualTo("catalog.throttled");
        handler = t -> new Reply(400, "{\"code\":\"client.legal_name.required\",\"detail\":\"Name is required\"}");
        assertThatThrownBy(() -> catalog.save(null, null)).hasMessageContaining("client.legal_name.required")
                .hasMessageNotContaining("Name is required");
    }

    @Test
    void getManyUsesBatchEndpointAndFallsBackToSingleCalls() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        handler = t -> t.startsWith("POST /api/clients/batch") ? new Reply(200, "[" + client(b) + "," + client(a) + "]")
                : new Reply(500, "");
        assertThat(catalog.getMany(List.of(a, b))).extracting(c -> c.id()).containsExactly(a, b);
        hits.clear();
        handler = t -> t.startsWith("POST") ? new Reply(404, "")
                : new Reply(200, client(UUID.fromString(t.substring(t.lastIndexOf('/') + 1))));
        assertThat(catalog.getMany(List.of(a, b))).hasSize(2);
        assertThat(hits).containsExactly("POST /api/clients/batch", "GET /api/clients/" + a, "GET /api/clients/" + b);
    }

    @Test
    void resolverBatchesAndMapsFailures() {
        var requests = java.util.stream.IntStream.range(0, 150).mapToObj(i -> new ClientResolutionRequest(List.of()))
                .toList();
        handler = t -> {
            var count = t.contains("batch") ? (hits.size() == 1 ? 100 : 50) : 1;
            var items = java.util.stream.IntStream.range(0, count).mapToObj(i -> "{\"method\":0,\"blockers\":[\"x\"]}")
                    .toList();
            return new Reply(200, "{\"results\":[" + String.join(",", items) + "]}");
        };
        assertThat(resolver.resolveBatch(requests)).hasSize(150);
        assertThat(hits).hasSize(2).allMatch(h -> h.endsWith("/resolve-client/batch"));

        handler = t -> new Reply(401, "");
        assertThat(resolver.resolve(new ClientResolutionRequest(List.of())).blockers())
                .containsExactly("client.resolution_authentication_required");
        server.stop(0);
        assertThat(resolver.resolveBatch(requests.subList(0, 3))).allSatisfy(r -> assertThat(r.blockers())
                .containsExactly("client.resolution_offline"));
        assertThat(Json.write(requests.getFirst())).isEqualTo("{\"fields\":[]}");
    }
}
