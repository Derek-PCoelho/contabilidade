package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.UUID;

public record UpsertClientCommand(
        UUID operationId,
        UUID clientId,
        String displayName,
        boolean isActive,
        long expectedVersion) {
}
