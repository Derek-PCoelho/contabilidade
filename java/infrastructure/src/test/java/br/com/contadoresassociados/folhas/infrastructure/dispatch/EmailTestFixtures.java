package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

final class EmailTestFixtures {

    static final UUID ITEM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final String FINGERPRINT = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    static final String CONTROLLED = "caixa.teste@escritorio.com.br";

    private EmailTestFixtures() {
    }

    static DispatchAttachmentSnapshot attachment(Path dir, String name, int size) throws IOException {
        var bytes = new byte[size];
        for (var i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        var path = dir.resolve(ProviderSupport.sha256Hex(name).substring(0, 12) + ".pdf");
        Files.write(path, bytes);
        return new DispatchAttachmentSnapshot(UUID.randomUUID(), path.toString(), name,
                ProviderSupport.sha256Hex(path), size, RecognizedDocumentType.values()[0]);
    }

    static EmailEnvelope envelope(DispatchOperationMode mode, List<String> to, List<DispatchAttachmentSnapshot> files,
            FakeDeliveryScenario scenario) {
        return new EmailEnvelope(ITEM, "folhas:" + mode + ":" + ITEM + ":1", mode, FINGERPRINT, "remetente@x.com",
                to, List.of(), "Folha de março — Empresa Ação", "Segue a folha.", "<p>Segue a folha.</p>", files,
                scenario);
    }

    static DeliveryAttempt attempt(DispatchOperationMode mode, String idempotencyKey, String draftId, String provider) {
        return new DeliveryAttempt(UUID.randomUUID(), UUID.randomUUID(), ITEM, UUID.randomUUID(), 1, mode,
                DeliveryAttemptState.AMBIGUOUS, provider, idempotencyKey, FINGERPRINT, null, draftId, null, null, null,
                null);
    }

    /** Sessão fixa para testes dos provedores. */
    static EmailAccountSession session(String providerKey, String email) {
        return new EmailAccountSession() {
            @Override
            public String providerKey() {
                return providerKey;
            }

            @Override
            public Status status() {
                return new Status(providerKey, true, true, email, email, null);
            }

            @Override
            public Status connect() {
                return status();
            }

            @Override
            public void disconnect() {
            }

            @Override
            public String accessToken(boolean requireSendPermission) {
                return requireSendPermission ? "token-send" : "token-draft";
            }
        };
    }
}
