package br.com.contadoresassociados.folhas.contracts.sync;

public record SyncNotification(
        long checkpoint,
        String entityType) {
}
