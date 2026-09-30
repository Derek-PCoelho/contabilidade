package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.List;

public record ClientSearchResponse(
        List<ClientListItem> items,
        int total,
        int skip,
        int take) {

    public ClientSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
