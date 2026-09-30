package br.com.contadoresassociados.folhas.contracts.documents;

import java.util.List;

public record ClientResolutionBatchResponse(
        List<ClientResolutionResult> results) {

    public ClientResolutionBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
