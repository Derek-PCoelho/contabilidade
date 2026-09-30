package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.UUID;

public record SyncCommandResult(
        UUID operationId,
        SyncCommandStatus status,
        ClientSyncItem current,
        String errorCode) {
}
