package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.CONTROLLED;
import static br.com.contadoresassociados.folhas.infrastructure.dispatch.EmailTestFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeEmailProviderTest {

    @TempDir
    Path dir;

    private FakeEmailProvider provider(Instant now) {
        return new FakeEmailProvider(new FakeEmailProvider.Options(dir, Duration.ZERO, Duration.ofDays(7)),
                Clock.fixed(now));
    }

    @Test
    void sameKeyReturnsSameReceiptAndTimeoutReconcilesAsAccepted() throws Exception {
        var fake = provider(Instant.now());
        var env = envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(), FakeDeliveryScenario.TIMEOUT);
        var first = fake.send(env);
        assertThat(first.state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(first.errorCode()).isEqualTo("FAKE_TIMEOUT_AFTER_ACCEPTANCE");
        var suffix = FakeEmailProvider.stableSuffix(env.idempotencyKey());
        assertThat(Files.exists(dir.resolve(suffix + ".json"))).isTrue();
        // repetir devolve o recibo gravado (aceito), nunca duplica
        assertThat(fake.send(env).state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        var attempt = EmailTestFixtures.attempt(DispatchOperationMode.SEND, env.idempotencyKey(), null, "fake.local");
        var reconciled = fake.reconcile(attempt);
        assertThat(reconciled.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);
        assertThat(reconciled.providerMessageId()).isEqualTo("fake-message-" + suffix);
    }

    @Test
    void receiptDoesNotStoreClearRecipientsOrBody() throws Exception {
        var fake = provider(Instant.now());
        var env = envelope(DispatchOperationMode.DRAFT, List.of("maria.silva@empresa.com.br"), List.of(),
                FakeDeliveryScenario.SUCCESS);
        var result = fake.createDraft(env);
        assertThat(result.state()).isEqualTo(DeliveryAttemptState.DRAFT_CREATED);
        assertThat(result.providerDraftId()).startsWith("fake-draft-");
        var json = Files.readString(dir.resolve(FakeEmailProvider.stableSuffix(env.idempotencyKey()) + ".json"));
        assertThat(json).contains("m***@empresa.com.br").doesNotContain("maria.silva").doesNotContain("Segue a folha")
                .doesNotContain("Empresa Ação").contains("\"result\"");
    }

    @Test
    void readsLegacyDotNetReceiptAndPurgesExpired() throws Exception {
        var key = "folhas:Send:legacy:1";
        var legacy = dir.resolve(FakeEmailProvider.stableSuffix(key) + ".json");
        Files.writeString(legacy, """
                {
                  "idempotencyKey": "folhas:Send:legacy:1",
                  "dispatchItemId": "11111111-2222-3333-4444-555555555555",
                  "to": ["a@b.com"], "cc": [],
                  "result": { "state": 2, "providerMessageId": "fake-message-x", "providerDraftId": null,
                              "errorCode": null, "redactedError": null },
                  "createdAtUtc": "2025-01-10T12:00:00+00:00"
                }
                """);
        var fake = provider(Instant.now());
        var attempt = EmailTestFixtures.attempt(DispatchOperationMode.SEND, key, null, "fake.local");
        assertThat(fake.reconcile(attempt).state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER);

        Files.setLastModifiedTime(legacy, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        assertThat(fake.purgeExpired()).isEqualTo(1);
        assertThat(fake.reconcile(attempt).errorCode()).isEqualTo("FAKE_RECEIPT_NOT_FOUND");
    }

    @Test
    void scenariosMapToExpectedStates() {
        var fake = provider(Instant.now());
        assertThat(fake.send(envelope(DispatchOperationMode.SEND, List.of(CONTROLLED), List.of(),
                FakeDeliveryScenario.TRANSIENT_FAILURE)).state()).isEqualTo(DeliveryAttemptState.FAILED_TRANSIENT);
        var permanent = new br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope(
                EmailTestFixtures.ITEM, "k-perm", DispatchOperationMode.SEND, "fp", "x", List.of(CONTROLLED), List.of(),
                "s", "t", "h", List.of(), FakeDeliveryScenario.PERMANENT_FAILURE);
        assertThat(fake.send(permanent).errorCode()).isEqualTo("FAKE_PERMANENT_FAILURE");
        var ambiguous = new br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope(
                EmailTestFixtures.ITEM, "k-amb", DispatchOperationMode.SEND, "fp", "x", List.of(CONTROLLED), List.of(),
                "s", "t", "h", List.of(), FakeDeliveryScenario.AMBIGUOUS);
        assertThat(fake.send(ambiguous).errorCode()).isEqualTo("FAKE_AMBIGUOUS_RESULT");
    }
}
