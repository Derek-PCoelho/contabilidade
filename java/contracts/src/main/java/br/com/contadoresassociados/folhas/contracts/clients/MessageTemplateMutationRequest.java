package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.UUID;

public record MessageTemplateMutationRequest(
        long expectedVersion,
        UUID clientId,
        UUID documentTypeId,
        String name,
        String subjectTemplate,
        String bodyTemplate,
        SignatureModeModel signatureMode,
        boolean isDefault,
        boolean isActive) {
}
