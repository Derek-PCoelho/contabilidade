package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MessageTemplateModel(
        UUID id,
        UUID clientId,
        UUID documentTypeId,
        String name,
        String subjectTemplate,
        String bodyTemplate,
        SignatureModeModel signatureMode,
        boolean isDefault,
        boolean isActive,
        long version,
        OffsetDateTime updatedAtUtc) {
}
