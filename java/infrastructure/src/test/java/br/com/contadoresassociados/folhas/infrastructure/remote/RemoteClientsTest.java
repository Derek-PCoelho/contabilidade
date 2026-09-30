package br.com.contadoresassociados.folhas.infrastructure.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.sync.SyncPorts;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightRequest;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncRequest;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import br.com.contadoresassociados.folhas.infrastructure.sync.HttpSyncTransport;
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

class RemoteClientsTest {

    record Hit(String method, String path, String auth, String body) {
    }

    record Reply(int status, String body) {
    }

    HttpServer server;
    final List<Hit> hits = new CopyOnWriteArrayList<>();
    volatile Function<Hit, Reply> handler;
    CentralApiClient api;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            var hit = new Hit(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    ex.getRequestHeaders().getFirst("Authorization"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            hits.add(hit);
            var reply = handler.apply(hit);
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        var vault = new InMemorySecretStore();
        vault.store(CentralApiClient.ACCESS_TOKEN_KEY, "sessao-123");
        api = new CentralApiClient(null, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), vault,
                Duration.ofSeconds(5), "1.0.0");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static EmailSendPreflightRequest request() {
        return new EmailSendPreflightRequest(UUID.randomUUID(), "google.gmail", "fp", 1, "1.0.0",
                DispatchOperationMode.SEND, 1, 1);
    }

    @Test
    void preflightSendsBearerAndMapsForbidden() {
        var req = request();
        handler = h -> new Reply(200, "{\"operationId\":\"" + req.operationId() + "\",\"authorized\":true,"
                + "\"emailSendEnabled\":true,\"minimumApplicationVersion\":\"1.0.0\",\"correlationId\":\""
                + UUID.randomUUID() + "\",\"errorCode\":null}");
        var guard = new HttpRemoteEmailSendGuard(api);
        assertThat(guard.authorize(req).authorized()).isTrue();
        assertThat(hits.getFirst().path()).isEqualTo("/api/email-dispatch/preflight");
        assertThat(hits.getFirst().auth()).isEqualTo("Bearer sessao-123");
        assertThat(hits.getFirst().body()).contains("\"operationMode\":2");

        handler = h -> new Reply(403, "");
        var denied = guard.authorize(req);
        assertThat(denied.authorized()).isFalse();
        assertThat(denied.errorCode()).isEqualTo("REMOTE_SEND_FORBIDDEN");
        assertThat(denied.operationId()).isEqualTo(req.operationId());

        handler = h -> new Reply(500, "");
        assertThatThrownBy(() -> guard.authorize(req)).isInstanceOf(CentralApiClient.CentralApiException.class);
    }

    @Test
    void batchPreflightFallsBackToSingleCallsWhenEndpointMissing() {
        var a = request();
        var b = request();
        handler = h -> {
            if (h.path().endsWith("/batch")) {
                return new Reply(404, "");
            }
            var id = h.body().contains(a.operationId().toString()) ? a.operationId() : b.operationId();
            return new Reply(200, "{\"operationId\":\"" + id + "\",\"authorized\":true,\"emailSendEnabled\":true,"
                    + "\"minimumApplicationVersion\":\"1.0.0\",\"correlationId\":\"" + UUID.randomUUID() + "\"}");
        };
        var results = new HttpRemoteEmailSendGuard(api).authorizeBatch(List.of(a, b));
        assertThat(results).extracting(r -> r.operationId()).containsExactly(a.operationId(), b.operationId());
        assertThat(hits).extracting(Hit::path).containsExactly("/api/email-dispatch/preflight/batch",
                "/api/email-dispatch/preflight", "/api/email-dispatch/preflight");
    }

    @Test
    void syncTransportWrapsFailures() {
        handler = h -> h.method().equals("GET") ? new Reply(200, "{\"checkpoint\":42,\"clients\":[],\"hasMore\":true}")
                : new Reply(503, "");
        var transport = new HttpSyncTransport(api);
        var pull = transport.pull(-5);
        assertThat(pull.checkpoint()).isEqualTo(42);
        assertThat(pull.hasMore()).isTrue();
        assertThat(hits.getFirst().path()).isEqualTo("/api/sync/clients?checkpoint=0");
        assertThatThrownBy(() -> transport.push(new PushSyncRequest(List.of())))
                .isInstanceOf(SyncPorts.SyncTransportException.class)
                .extracting(e -> ((SyncPorts.SyncTransportException) e).statusCode()).isEqualTo(503);
        handler = h -> new Reply(200, "nao-json");
        assertThatThrownBy(() -> transport.pull(1)).isInstanceOf(SyncPorts.SyncTransportException.class);
        server.stop(0);
        assertThatThrownBy(() -> transport.pull(1)).isInstanceOf(SyncPorts.SyncTransportException.class)
                .extracting(e -> ((SyncPorts.SyncTransportException) e).statusCode()).isEqualTo(0);
    }

    @Test
    void insecureRemoteAddressIsRejected() {
        assertThatThrownBy(() -> new CentralApiClient(null, URI.create("http://api.empresa.com"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
    }
}
