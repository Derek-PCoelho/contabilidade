package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.List;

public record PushSyncResponse(
        List<SyncCommandResult> results) {

    public PushSyncResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
