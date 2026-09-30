package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RenderedMessageSnapshot(
        UUID subjectTemplateId,
        long subjectTemplateVersion,
        String subjectTemplateName,
        UUID bodyTemplateId,
        long bodyTemplateVersion,
        String bodyTemplateName,
        String senderAccountId,
        List<DispatchRecipientSnapshot> originalTo,
        List<DispatchRecipientSnapshot> originalCc,
        List<String> effectiveTo,
        List<String> effectiveCc,
        String subject,
        String textBody,
        String htmlBody,
        List<DispatchAttachmentSnapshot> attachments,
        String dispatchFingerprint,
        OffsetDateTime renderedAtUtc) {

    public RenderedMessageSnapshot {
        originalTo = originalTo == null ? List.of() : List.copyOf(originalTo);
        originalCc = originalCc == null ? List.of() : List.copyOf(originalCc);
        effectiveTo = effectiveTo == null ? List.of() : List.copyOf(effectiveTo);
        effectiveCc = effectiveCc == null ? List.of() : List.copyOf(effectiveCc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
