package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.CONTROLLED;
import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.attachment;
import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession.EmailAccountSessionException;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.infrastructure.dispatch.StubServer.Reply;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicrosoftGraphEmailProviderTest {

    static final String CLIENT_ID = "0f3b1c2d-1111-2222-3333-444455556666";

    @TempDir
    Path dir;

    StubServer server;
    MicrosoftGraphOptions options;

    @BeforeAll
    static void allowLoopback() {
        System.setProperty("folhas.test.allowLoopbackProviders", "true");
    }

    @BeforeEach
    void start() throws Exception {
        server = new StubServer();
        options = MicrosoftGraphOptions.of(true, true, CLIENT_ID, CONTROLLED).withApiBaseAddress(server.uri("/v1.0/"));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private MicrosoftGraphEmailProvider provider(boolean production) {
        return new MicrosoftGraphEmailProvider(ProviderHttp.defaultClient(),
                EmailTestFixtures.session(DispatchWorkflowOptions.GRAPH_PROVIDER, "conta@empresa.onmicrosoft.com"),
                options, () -> production, ProviderHttp.Sleeper.NONE);
    }

    @Test
    void createsDraftWithIdempotencyPropertyUploadsLargeAttachmentAndSends() throws Exception {
        var small = attachment(dir, "pequeno.pdf", 1024);
        var large = attachment(dir, "grande.pdf", MicrosoftGraphEmailProvider.UPLOAD_CHUNK_SIZE_BYTES + 10);
        var ranges = new ArrayList<String>();
        var created = new String[1];
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /v1.0/me/messages" -> Reply.json(200, "{\"value\":[]}");
            case "POST /v1.0/me/messages" -> {
                created[0] = r.text();
                yield Reply.json(201, "{\"id\":\"msg-1\"}");
            }
            case "GET /v1.0/me/messages/msg-1/attachments" -> Reply.json(200, "{\"value\":[]}");
            case "POST /v1.0/me/messages/msg-1/attachments" -> Reply.json(201, "{}");
            case "POST /v1.0/me/messages/msg-1/attachments/createUploadSession" ->
                Reply.json(201, "{\"uploadUrl\":\"" + server.uri("/upload/s1") + "\"}");
            case "PUT /upload/s1" -> {
                assertThat(r.header("Authorization")).isNull();
                ranges.add(r.header("Content-Range"));
                yield Reply.json(ranges.size() == 1 ? 200 : 201, "{}");
            }
            case "POST /v1.0/me/messages/msg-1/send" -> {
                assertThat(r.header("Authorization")).isEqualTo("Bearer token-send");
                yield Reply.of(202);
            }
            default -> Reply.of(404);
        });
        var result = provider(false).send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED),
                List.of(small, large), FakeDeliveryScenario.SUCCESS));
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(result.providerMessageId()).isEqualTo("msg-1");
        assertThat(created[0]).contains(MicrosoftGraphEmailProvider.IDEMPOTENCY_PROPERTY_ID)
                .contains("folhas:Send:11111111222233334444555555555555:" + EmailTestFixtures.FINGERPRINT)
                .contains("\"contentType\":\"HTML\"");
        var total = MicrosoftGraphEmailProvider.UPLOAD_CHUNK_SIZE_BYTES + 10;
        assertThat(ranges).containsExactly("bytes 0-" + (MicrosoftGraphEmailProvider.UPLOAD_CHUNK_SIZE_BYTES - 1) + "/"
                + total, "bytes " + MicrosoftGraphEmailProvider.UPLOAD_CHUNK_SIZE_BYTES + "-" + (total - 1) + "/" + total);
        var filter = server.requests.stream().filter(r -> r.target().startsWith("GET /v1.0/me/messages?")).findFirst()
                .orElseThrow().query();
        assertThat(URLDecoder.decode(filter, StandardCharsets.UTF_8)).contains("ep/value eq 'folhas:Send:");
    }

    @Test
    void existingSentMessageIsNotDuplicatedAndExistingAttachmentsAreSkipped() throws Exception {
        server.handle(r -> Reply.json(200, "{\"value\":[{\"id\":\"sent-1\",\"isDraft\":false}]}"));
        var sent = provider(false).send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(sent.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(server.targets()).noneMatch(t -> t.startsWith("POST"));

        var file = attachment(dir, "a.pdf", 500);
        server.requests.clear();
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /v1.0/me/messages" -> Reply.json(200, "{\"value\":[{\"id\":\"d-7\",\"isDraft\":true}]}");
            case "GET /v1.0/me/messages/d-7/attachments" -> Reply.json(200, "{\"value\":[{\"contentId\":\""
                    + MicrosoftGraphEmailProvider.contentId(file.sha256()) + "\"}]}");
            default -> Reply.of(500);
        });
        var draft = provider(false).createDraft(envelope(DispatchOperationMode.DRAFT, List.of(CONTROLLED),
                List.of(file), FakeDeliveryScenario.SUCCESS));
        assertThat(draft.state()).isEqualTo(DeliveryAttemptState.DRAFT_CREATED);
        assertThat(draft.providerDraftId()).isEqualTo("d-7");
        assertThat(server.targets()).noneMatch(t -> t.startsWith("POST"));
    }

    @Test
    void reconcileAndFailureMapping() {
        server.handle(r -> Reply.json(200, "{\"value\":[{\"id\":\"d-1\",\"isDraft\":true}]}"));
        var attempt = EmailTestFixtures.attempt(DispatchOperationMode.SEND, "k", null, "microsoft.graph");
        assertThat(provider(false).reconcile(attempt).errorCode()).isEqualTo("GRAPH_DRAFT_CONFIRMED_NOT_SENT");
        server.handle(r -> Reply.json(200, "{\"value\":[]}"));
        assertThat(provider(false).reconcile(attempt).errorCode()).isEqualTo("GRAPH_MESSAGE_NOT_FOUND");
        server.handle(r -> Reply.json(200, "{\"value\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"));
        assertThat(provider(false).reconcile(attempt).state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);

        server.handle(r -> r.method().equals("GET") ? Reply.json(200, "{\"value\":[]}") : Reply.json(401, "{}"));
        assertThat(provider(false).createDraft(envelope(DispatchOperationMode.DRAFT, List.of(CONTROLLED), List.of(),
                FakeDeliveryScenario.SUCCESS)).errorCode()).isEqualTo("AUTH_REVOKED");

        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /v1.0/me/messages" -> Reply.json(200, "{\"value\":[]}");
            case "POST /v1.0/me/messages" -> Reply.json(201, "{\"id\":\"m\"}");
            case "GET /v1.0/me/messages/m/attachments" -> Reply.json(200, "{\"value\":[]}");
            default -> Reply.json(504, "{}");
        });
        var send = provider(false).send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(),
                FakeDeliveryScenario.SUCCESS));
        assertThat(send.state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(send.providerDraftId()).isEqualTo("m");

        var blocked = provider(false).send(envelope(DispatchOperationMode.SEND, List.of("cliente@empresa.com"),
                List.of(), FakeDeliveryScenario.SUCCESS));
        assertThat(blocked.errorCode()).isEqualTo("GRAPH_RECIPIENT_NOT_CONTROLLED");
    }

    @Test
    void nextLinkToForeignHostIsRejected() throws Exception {
        var file = attachment(dir, "a.pdf", 10);
        server.handle(r -> switch (r.method() + " " + r.path()) {
            case "GET /v1.0/me/messages" -> Reply.json(200, "{\"value\":[{\"id\":\"d\",\"isDraft\":true}]}");
            case "GET /v1.0/me/messages/d/attachments" ->
                Reply.json(200, "{\"value\":[],\"@odata.nextLink\":\"https://evil.example/steal\"}");
            default -> Reply.of(500);
        });
        var result = provider(false).createDraft(envelope(DispatchOperationMode.DRAFT, List.of(CONTROLLED),
                List.of(file), FakeDeliveryScenario.SUCCESS));
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(server.targets()).noneMatch(t -> t.contains("steal"));
    }

    @Test
    void sessionTranslatesMsalOutcomes() {
        var vault = new InMemorySecretStore();
        vault.store(MicrosoftGraphEmailAccountSession.TOKEN_CACHE_KEY, "cache");
        var account = new MicrosoftGraphEmailAccountSession.MsalGateway.Account("user@empresa.com", null);
        var mode = new String[] {"ok"};
        var gateway = new MicrosoftGraphEmailAccountSession.MsalGateway() {
            @Override
            public Optional<Account> firstAccount() {
                return mode[0].equals("none") ? Optional.empty() : Optional.of(account);
            }

            @Override
            public Account interactive(Set<String> scopes) {
                if (mode[0].equals("cancel")) {
                    throw new Cancelled(null);
                }
                assertThat(scopes).containsExactlyInAnyOrder("Mail.ReadWrite", "Mail.Send");
                return account;
            }

            @Override
            public String silent(Set<String> scopes, Account a) {
                if (mode[0].equals("revoked")) {
                    throw new InteractionRequired(null);
                }
                return "tok-" + scopes.size();
            }

            @Override
            public void removeAll() {
                throw new IllegalStateException("msal quebrado");
            }
        };
        var session = new MicrosoftGraphEmailAccountSession(options, vault, gateway);
        assertThat(session.connect().accountId()).isEqualTo("user@empresa.com");
        assertThat(session.accessToken(true)).isEqualTo("tok-2");
        assertThat(session.accessToken(false)).isEqualTo("tok-1");
        mode[0] = "revoked";
        assertThatThrownBy(() -> session.accessToken(false)).isInstanceOf(EmailAccountSessionException.class)
                .extracting(e -> ((EmailAccountSessionException) e).code()).isEqualTo("AUTH_REVOKED");
        mode[0] = "cancel";
        assertThat(session.connect().errorCode()).isEqualTo("AUTH_CANCELLED");
        mode[0] = "none";
        assertThat(session.status().errorCode()).isEqualTo("AUTH_REQUIRED");
        session.disconnect();
        assertThat(vault.retrieve(MicrosoftGraphEmailAccountSession.TOKEN_CACHE_KEY)).isEmpty();

        var notConfigured = new MicrosoftGraphEmailAccountSession(MicrosoftGraphOptions.of(true, true, "nao-guid",
                CONTROLLED), vault);
        assertThat(notConfigured.status().errorCode()).isEqualTo("GRAPH_NOT_CONFIGURED");
    }
}
