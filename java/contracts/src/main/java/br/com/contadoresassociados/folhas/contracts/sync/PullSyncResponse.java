package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.List;

/** Pull paginado por checkpoint por organização (pendências 3.1 e 3.14). */
public record PullSyncResponse(
        long checkpoint,
        List<ClientSyncItem> clients,
        boolean hasMore) {

    public PullSyncResponse {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }
}
