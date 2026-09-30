package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.List;
import java.util.UUID;

public record ClientReadinessResponse(
        UUID clientId,
        boolean isEligible,
        List<String> blockCodes) {

    public ClientReadinessResponse {
        blockCodes = blockCodes == null ? List.of() : List.copyOf(blockCodes);
    }
}
