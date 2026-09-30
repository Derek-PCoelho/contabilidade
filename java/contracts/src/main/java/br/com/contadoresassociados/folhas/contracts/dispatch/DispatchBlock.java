package br.com.contadoresassociados.folhas.contracts.dispatch;

import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;

public record DispatchBlock(
        String code,
        ValidationSeverity severity,
        String message) {
}
