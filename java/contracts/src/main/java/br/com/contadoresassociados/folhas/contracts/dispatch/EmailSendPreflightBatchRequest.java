package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.List;

/** Pendência 4.7: preflight em lote (um pedido por lote, não por item). */
public record EmailSendPreflightBatchRequest(
        List<EmailSendPreflightRequest> items) {

    public EmailSendPreflightBatchRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
