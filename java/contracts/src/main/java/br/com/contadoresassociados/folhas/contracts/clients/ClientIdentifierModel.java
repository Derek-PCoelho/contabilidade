package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.UUID;

public record ClientIdentifierModel(
        UUID id,
        ClientIdentifierTypeModel type,
        String value,
        ClientIdentifierSemanticRoleModel semanticRole,
        int priority,
        boolean isActive,
        boolean isUniqueWithinOrganization) {
}
