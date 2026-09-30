package br.com.contadoresassociados.folhas.contracts.documents;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ClientResolutionResult(
        UUID clientId,
        UUID establishmentId,
        String clientDisplayName,
        String clientTaxIdMasked,
        ClientResolutionMethod method,
        BigDecimal confidence,
        List<EvidenceBox> evidence,
        List<ClientResolutionCandidate> alternatives,
        List<String> blockers) {

    public ClientResolutionResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isResolved() {
        return clientId != null && blockers.isEmpty();
    }

    public static ClientResolutionResult unresolved(String... blockers) {
        return new ClientResolutionResult(null, null, null, null, ClientResolutionMethod.NONE, BigDecimal.ZERO,
                List.of(), List.of(), List.of(blockers));
    }
}
