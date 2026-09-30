package br.com.contadoresassociados.folhas.domain.clients;

import java.util.UUID;

public record MessageTemplateDraft(
        UUID clientId,
        UUID documentTypeId,
        String name,
        String subjectTemplate,
        String bodyTemplate,
        SignatureMode signatureMode,
        boolean isDefault,
        boolean active) {
}
