package br.com.contadoresassociados.folhas.contracts.documents;

import java.util.List;

/** Pendência 4.6: resolução em lote (uma chamada por revalidação, não por documento). */
public record ClientResolutionBatchRequest(
        List<ClientResolutionRequest> items) {

    public ClientResolutionBatchRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
