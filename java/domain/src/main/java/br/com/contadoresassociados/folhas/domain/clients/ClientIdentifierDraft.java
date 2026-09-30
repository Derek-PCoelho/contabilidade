package br.com.contadoresassociados.folhas.domain.clients;

import java.util.UUID;

public record ClientIdentifierDraft(
        UUID id,
        ClientIdentifierType type,
        String value,
        ClientIdentifierSemanticRole semanticRole,
        int priority,
        boolean active,
        boolean uniqueWithinOrganization) {
}
