package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.CONTROLLED;
import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.attachment;
import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.infrastructure.dispatch.StubServer.Reply;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GmailEmailProviderTest {

    @TempDir
    Path dir;

    StubServer server;
    GmailOptions options;

    @BeforeAll
    static void allowLoopback() {
        System.setProperty("folhas.test.allowLoopbackProviders", "true");
    }

    @BeforeEach
    void start() throws Exception {
        server = new StubServer();
        options = GmailOptions.of(true, true, "123.apps.googleusercontent.com", CONTROLLED).withEndpoints(
                server.uri("/auth"), server.uri("/token"), server.uri("/revoke"), server.uri("/gmail/v1/"),
                server.uri("/upload/gmail/v1/")).withSentVerificationScope(GmailOptions.READONLY_SCOPE);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private GmailEmailProvider provider(GmailOptions opts, boolean production) {
        return new GmailEmailProvider(ProviderHttp.defaultClient(),
                EmailTestFixtures.session(DispatchWorkflowOptions.GMAIL_PROVIDER, "conta@gmail.com"), opts,
                () -> production, ProviderHttp.Sleeper.NONE);
    }

    @Test
    void createsDraftWithDeterministicMessageIdAndSendsIt() throws Exception {
        var file = attachment(dir, "folha março.pdf", 2048);
        var raw = new String[1];
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /gmail/v1/users/me/messages", "GET /gmail/v1/users/me/drafts" -> Reply.json(200, "{}");
            case "POST /gmail/v1/users/me/drafts" -> {
                raw[0] = r.text();
                yield Reply.json(200, "{\"id\":\"d1\",\"message\":{\"id\":\"m1\"}}");
            }
            case "POST /gmail/v1/users/me/drafts/send" -> {
                assertThat(r.header("Authorization")).isEqualTo("Bearer token-send");
                yield Reply.json(200, "{\"id\":\"sent-1\"}");
            }
            default -> Reply.of(404);
        });
        var result = provider(options, false).send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED),
                List.of(file), FakeDeliveryScenario.SUCCESS));
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(result.providerMessageId()).isEqualTo("sent-1");
        assertThat(result.providerDraftId()).isEqualTo("d1");

        var rawValue = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw[0]).path("message").path("raw")
                .asText();
        var mime = new String(Base64.getUrlDecoder().decode(rawValue), StandardCharsets.UTF_8);
        assertThat(mime).contains("Message-ID: <folhas.send.11111111222233334444555555555555."
                + EmailTestFixtures.FINGERPRINT.substring(0, 32) + "@folhas.invalid>");
        assertThat(mime).contains("X-Folhas-Idempotency: folhas:Send:11111111222233334444555555555555:"
                + EmailTestFixtures.FINGERPRINT);
        assertThat(mime).contains("To: " + CONTROLLED).contains("application/pdf").contains("filename=\"=?UTF-8?");
        var sentQuery = server.requests.stream().filter(r -> r.path().endsWith("/users/me/messages")).findFirst()
                .orElseThrow().query();
        assertThat(URLDecoder.decode(sentQuery, StandardCharsets.UTF_8)).contains("in:sent rfc822msgid:<folhas.send.");
    }

    @Test
    void alreadySentMessageIsNeverDraftedAgain() {
        server.handle(r -> r.path().endsWith("/users/me/messages")
                ? Reply.json(200, "{\"messages\":[{\"id\":\"sent-9\"}]}") : Reply.of(500));
        var result = provider(options, false).send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(result.providerMessageId()).isEqualTo("sent-9");
        assertThat(server.targets()).noneMatch(t -> t.startsWith("POST"));
    }

    @Test
    void reconcileProvesSentWhenDraftDisappeared() {
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /gmail/v1/users/me/drafts/d1" -> Reply.of(404);
            case "GET /gmail/v1/users/me/messages" -> Reply.json(200, "{\"messages\":[{\"id\":\"sent-2\"}]}");
            default -> Reply.of(404);
        });
        var attempt = EmailTestFixtures.attempt(DispatchOperationMode.SEND, "k", "d1", "google.gmail");
        var result = provider(options, false).reconcile(attempt);
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(result.providerMessageId()).isEqualTo("sent-2");

        // sem o escopo de leitura: comportamento anterior (ambíguo)
        var legacy = provider(options.withSentVerificationScope(null), false).reconcile(attempt);
        assertThat(legacy.state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(legacy.errorCode()).isEqualTo("GMAIL_SENT_OR_DELETED_UNKNOWN");
    }

    @Test
    void largeMessageUsesResumableUpload() throws Exception {
        var file = attachment(dir, "grande.pdf", 64 * 1024);
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /gmail/v1/users/me/messages", "GET /gmail/v1/users/me/drafts" -> Reply.json(200, "{}");
            case "POST /upload/gmail/v1/users/me/drafts" -> {
                assertThat(r.query()).isEqualTo("uploadType=resumable");
                assertThat(r.header("X-Upload-Content-Type")).isEqualTo("message/rfc822");
                yield new Reply(200, "", Map.of("Location", server.uri("/upload/gmail/v1/session/abc").toString()));
            }
            case "PUT /upload/gmail/v1/session/abc" -> {
                assertThat(r.text()).contains("Message-ID: <folhas.draft.");
                yield Reply.json(200, "{\"id\":\"d-big\",\"message\":{\"id\":\"m-big\"}}");
            }
            default -> Reply.of(404);
        });
        var result = provider(options.withResumableThreshold(16 * 1024), false).createDraft(envelope(
                DispatchOperationMode.DRAFT, List.of(CONTROLLED), List.of(file), FakeDeliveryScenario.SUCCESS));
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.DRAFT_CREATED);
        assertThat(result.providerDraftId()).isEqualTo("d-big");
        assertThat(server.targets()).noneMatch(t -> t.startsWith("POST /gmail/v1/users/me/drafts"));
    }

    @Test
    void recipientRulesFollowProductionRollout() {
        server.handle(r -> Reply.json(200, "{}"));
        var real = List.of("cliente@empresa.com.br", "financeiro@empresa.com.br");
        var draft = provider(options, true).createDraft(envelope(DispatchOperationMode.DRAFT, real, List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(draft.errorCode()).isEqualTo("GMAIL_RECIPIENT_NOT_CONTROLLED");
        var notReady = provider(options, false).send(envelope(DispatchOperationMode.SEND, real, List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(notReady.errorCode()).isEqualTo("GMAIL_RECIPIENT_NOT_CONTROLLED");
        assertThat(server.requests).isEmpty();

        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "POST /gmail/v1/users/me/drafts" -> Reply.json(200, "{\"id\":\"d\",\"message\":{\"id\":\"m\"}}");
            case "POST /gmail/v1/users/me/drafts/send" -> Reply.json(200, "{\"id\":\"s\"}");
            default -> Reply.json(200, "{}");
        });
        var production = provider(options, true).send(envelope(DispatchOperationMode.SEND, real, List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(production.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
    }

    @Test
    void failuresAreClassifiedAndNeverThrown() throws Exception {
        var calls = new AtomicBoolean();
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /gmail/v1/users/me/messages", "GET /gmail/v1/users/me/drafts" -> Reply.json(200, "{}");
            case "POST /gmail/v1/users/me/drafts" -> Reply.json(200, "{\"id\":\"d\",\"message\":{\"id\":\"m\"}}");
            case "POST /gmail/v1/users/me/drafts/send" -> {
                calls.set(true);
                yield Reply.json(503, "{}");
            }
            default -> Reply.of(404);
        });
        var env = envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(), FakeDeliveryScenario.SUCCESS);
        var unknown = provider(options, false).send(env);
        assertThat(calls).isTrue();
        assertThat(unknown.state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(unknown.errorCode()).isEqualTo("GMAIL_RESULT_UNKNOWN");
        assertThat(unknown.providerDraftId()).isEqualTo("d");

        server.handle(r -> r.method().equals("GET") ? Reply.json(200, "{}")
                : r.path().endsWith("/drafts/send") ? Reply.json(200, "{\"sem-id\":true}")
                : Reply.json(200, "{\"id\":\"d\",\"message\":{\"id\":\"m\"}}"));
        assertThat(provider(options, false).send(env).errorCode()).isEqualTo("GMAIL_SEND_RESULT_UNKNOWN");

        server.handle(r -> r.method().equals("GET") ? Reply.json(200, "{}") : new Reply(429, "", Map.of("Retry-After", "7")));
        var throttled = provider(options, false).createDraft(env);
        assertThat(throttled.state()).isEqualTo(DeliveryAttemptState.FAILED_TRANSIENT);
        assertThat(throttled.redactedError()).contains("7 segundo");

        var file = attachment(dir, "a.pdf", 100);
        Files.write(Path.of(file.localPath()), new byte[100]);
        var changed = provider(options, false).createDraft(envelope(DispatchOperationMode.DRAFT, List.of(CONTROLLED),
                List.of(file), FakeDeliveryScenario.SUCCESS));
        assertThat(changed.errorCode()).isEqualTo("ATTACHMENT_READ_FAILED");

        var fakeScenario = provider(options, false).createDraft(envelope(DispatchOperationMode.DRAFT,
                List.of(CONTROLLED), List.of(), FakeDeliveryScenario.TIMEOUT));
        assertThat(fakeScenario.errorCode()).isEqualTo("GMAIL_FAKE_SCENARIO_FORBIDDEN");
    }

    @Test
    void retrySafeLookupsRetryOnTransientStatus() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        server.handle(r -> {
            if (r.path().endsWith("/users/me/drafts/d1")) {
                return attempts.incrementAndGet() < 3 ? Reply.json(503, "{}") : Reply.json(200, "{\"id\":\"d1\"}");
            }
            return Reply.of(404);
        });
        var attempt = EmailTestFixtures.attempt(DispatchOperationMode.DRAFT, "k", "d1", "google.gmail");
        var result = provider(options, false).reconcile(attempt);
        assertThat(result.errorCode()).isEqualTo("GMAIL_DRAFT_CONFIRMED_NOT_SENT");
        assertThat(attempts.get()).isEqualTo(3);
    }
}
