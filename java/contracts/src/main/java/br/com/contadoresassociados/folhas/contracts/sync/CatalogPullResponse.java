package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.List;

public record CatalogPullResponse(
        long checkpoint,
        List<CatalogChange> changes,
        boolean hasMore) {

    public CatalogPullResponse {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
