package br.com.contadoresassociados.folhas.contracts.documents;

import java.util.List;

public record ClientResolutionRequest(
        List<RecognizedField> fields) {

    public ClientResolutionRequest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
