package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.CONTROLLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession.EmailAccountSessionException;
import br.com.contadoresassociados.folhas.infrastructure.dispatch.StubServer.Reply;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GmailEmailAccountSessionTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    StubServer server;
    GmailOptions options;
    InMemorySecretStore vault;

    @BeforeAll
    static void allowLoopback() {
        System.setProperty("folhas.test.allowLoopbackProviders", "true");
    }

    @BeforeEach
    void start() throws Exception {
        server = new StubServer();
        vault = new InMemorySecretStore();
        options = GmailOptions.of(true, true, "123.apps.googleusercontent.com", CONTROLLED).withEndpoints(
                server.uri("/auth"), server.uri("/token"), server.uri("/revoke"), server.uri("/gmail/v1/"),
                server.uri("/upload/gmail/v1/"));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    static Map<String, String> form(String body) {
        var map = new HashMap<String, String>();
        for (var pair : body.split("&")) {
            var kv = pair.split("=", 2);
            map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    private GmailEmailAccountSession session(OAuthAuthorizationReceiver receiver) {
        return new GmailEmailAccountSession(ProviderHttp.defaultClient(), options, vault, Clock.fixed(NOW), receiver);
    }

    @Test
    void connectUsesPkceAndClientSecretFromVaultAndStoresDotNetCompatibleToken() {
        vault.store(GmailOptions.CLIENT_SECRET_KEY, "GOCSPX-segredo");
        var tokenForm = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
        server.handle(r -> switch (r.path()) {
            case "/token" -> {
                tokenForm.set(form(r.text()));
                yield Reply.json(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600,"
                        + "\"scope\":\"" + GmailOptions.COMPOSE_SCOPE + "\"}");
            }
            case "/gmail/v1/users/me/profile" -> Reply.json(200, "{\"emailAddress\":\"conta@gmail.com\"}");
            default -> Reply.of(404);
        });
        var authUri = new URI[1];
        var status = session((create, state, ct) -> {
            var redirect = URI.create("http://127.0.0.1:5555/oauth2/callback/");
            authUri[0] = create.apply(redirect);
            return new OAuthAuthorizationReceiver.AuthorizationResponse("code-1", state, null, redirect);
        }).connect();

        assertThat(status.connected()).isTrue();
        assertThat(status.accountId()).isEqualTo("conta@gmail.com");
        var query = OAuthAuthorizationReceiver.SystemBrowser.parseQuery(authUri[0].getRawQuery());
        assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent").containsEntry("scope", GmailOptions.COMPOSE_SCOPE);
        assertThat(tokenForm.get()).containsEntry("client_secret", "GOCSPX-segredo").containsEntry("code", "code-1")
                .containsKey("code_verifier");
        var stored = vault.retrieve(GmailEmailAccountSession.TOKEN_CACHE_KEY).orElseThrow();
        assertThat(stored).contains("\"accessToken\":\"at\"").contains("\"refreshToken\":\"rt\"")
                .contains("\"accountEmail\":\"conta@gmail.com\"").contains("\"expiresAtUtc\":\"2026-09-30T13:00:00Z\"");
    }

    @Test
    void readsTokenWrittenByDotNetAndRefreshesWithClientSecret() {
        vault.store(GmailEmailAccountSession.TOKEN_CACHE_KEY, "{\"accessToken\":\"old\",\"refreshToken\":\"rt\","
                + "\"expiresAtUtc\":\"2026-09-30T12:01:00+00:00\",\"scope\":\"x\",\"accountEmail\":\"conta@gmail.com\"}");
        options = options.withClientSecret("segredo-config");
        var refreshForm = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
        server.handle(r -> {
            refreshForm.set(form(r.text()));
            return Reply.json(200, "{\"access_token\":\"new\",\"expires_in\":3600}");
        });
        var s = session(null);
        assertThat(s.status().accountId()).isEqualTo("conta@gmail.com");
        assertThat(s.accessToken(false)).isEqualTo("new");
        assertThat(refreshForm.get()).containsEntry("grant_type", "refresh_token")
                .containsEntry("client_secret", "segredo-config");
        assertThat(vault.retrieve(GmailEmailAccountSession.TOKEN_CACHE_KEY).orElseThrow()).contains("\"new\"");
    }

    @Test
    void revokedRefreshRemovesTokenAndCorruptTokenIsDiscarded() {
        vault.store(GmailEmailAccountSession.TOKEN_CACHE_KEY, "{\"accessToken\":\"old\",\"refreshToken\":\"rt\","
                + "\"expiresAtUtc\":\"2020-01-01T00:00:00Z\",\"scope\":\"x\",\"accountEmail\":\"a@b.com\"}");
        server.handle(r -> Reply.json(400, "{\"error\":\"invalid_grant\"}"));
        var s = session(null);
        assertThatThrownBy(() -> s.accessToken(false)).isInstanceOf(EmailAccountSessionException.class)
                .extracting(e -> ((EmailAccountSessionException) e).code()).isEqualTo("AUTH_REVOKED");
        assertThat(vault.retrieve(GmailEmailAccountSession.TOKEN_CACHE_KEY)).isEmpty();

        vault.store(GmailEmailAccountSession.TOKEN_CACHE_KEY, "{corrompido");
        assertThat(s.status().errorCode()).isEqualTo("AUTH_REQUIRED");
        assertThat(vault.retrieve(GmailEmailAccountSession.TOKEN_CACHE_KEY)).isEmpty();
    }

    @Test
    void connectFailuresReturnStableCodes() {
        server.handle(r -> r.path().equals("/token")
                ? Reply.json(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":10,\"scope\":\"email\"}")
                : Reply.of(404));
        var redirect = URI.create("http://127.0.0.1:5555/oauth2/callback/");
        assertThat(session((c, st, ct) -> new OAuthAuthorizationReceiver.AuthorizationResponse("c", st, null, redirect))
                .connect().errorCode()).isEqualTo("AUTH_SCOPE_MISSING");
        assertThat(session((c, st, ct) -> new OAuthAuthorizationReceiver.AuthorizationResponse("c", "outro", null,
                redirect)).connect().errorCode()).isEqualTo("AUTH_STATE_INVALID");
        assertThat(session((c, st, ct) -> new OAuthAuthorizationReceiver.AuthorizationResponse(null, st,
                "access_denied", redirect)).connect().errorCode()).isEqualTo("AUTH_CANCELLED");
        server.handle(r -> Reply.json(200, "{\"sem\":\"campos\"}"));
        assertThat(session((c, st, ct) -> new OAuthAuthorizationReceiver.AuthorizationResponse("c", st, null, redirect))
                .connect().errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void disconnectRevokesAndAlwaysClearsVault() {
        vault.store(GmailEmailAccountSession.TOKEN_CACHE_KEY, "{\"accessToken\":\"at\",\"refreshToken\":\"rt\","
                + "\"expiresAtUtc\":\"2030-01-01T00:00:00Z\",\"scope\":\"x\",\"accountEmail\":\"a@b.com\"}");
        server.handle(r -> Reply.of(503));
        session(null).disconnect();
        assertThat(server.requests).singleElement().satisfies(r -> assertThat(form(r.text())).containsEntry("token", "rt"));
        assertThat(vault.retrieve(GmailEmailAccountSession.TOKEN_CACHE_KEY)).isEmpty();
    }

    @Test
    void loopbackReceiverServesPortuguesePageAndReturnsCode() {
        var receiver = new OAuthAuthorizationReceiver.SystemBrowser(java.time.Duration.ofSeconds(10), uri -> {
            var callback = URI.create(uri.getQuery().replace("redirect=", "") + "?code=abc&state=s1");
            Thread.ofVirtual().start(() -> {
                try {
                    var response = java.net.http.HttpClient.newHttpClient().send(
                            java.net.http.HttpRequest.newBuilder(callback).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.body()).contains("Conta conectada");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        });
        var response = receiver.receive(r -> URI.create("https://auth.example/?redirect=" + r), "s1",
                br.com.contadoresassociados.folhas.application.common.CancellationToken.NONE);
        assertThat(response.code()).isEqualTo("abc");
        assertThat(response.state()).isEqualTo("s1");
        assertThat(response.redirectUri().getHost()).isEqualTo("127.0.0.1");
    }
}
