package br.com.contadoresassociados.folhas.contracts.documents;

import java.math.BigDecimal;
import java.util.UUID;

public record ClientResolutionCandidate(
        UUID clientId,
        UUID establishmentId,
        String displayName,
        String taxIdMasked,
        ClientResolutionMethod method,
        BigDecimal confidence) {
}
