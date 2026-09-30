package br.com.contadoresassociados.folhas.contracts.documents;

import java.math.BigDecimal;

public record EvidenceBox(
        int pageNumber,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        String snippet) {
}
