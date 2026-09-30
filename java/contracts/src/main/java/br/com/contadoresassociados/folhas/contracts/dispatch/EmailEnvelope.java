package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.List;
import java.util.UUID;

public record EmailEnvelope(
        UUID dispatchItemId,
        String idempotencyKey,
        DispatchOperationMode operationMode,
        String dispatchFingerprint,
        String senderAccountId,
        List<String> to,
        List<String> cc,
        String subject,
        String textBody,
        String htmlBody,
        List<DispatchAttachmentSnapshot> attachments,
        FakeDeliveryScenario scenario) {

    public EmailEnvelope {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
