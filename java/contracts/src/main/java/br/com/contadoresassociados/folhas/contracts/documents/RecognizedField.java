package br.com.contadoresassociados.folhas.contracts.documents;

import java.math.BigDecimal;

public record RecognizedField(
        String name,
        String value,
        String displayValue,
        SemanticFieldRole role,
        BigDecimal confidence,
        EvidenceBox evidence) {
}
