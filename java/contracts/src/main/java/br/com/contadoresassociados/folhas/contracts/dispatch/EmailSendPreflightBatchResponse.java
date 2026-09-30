package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.List;

public record EmailSendPreflightBatchResponse(
        List<EmailSendPreflightResponse> results) {

    public EmailSendPreflightBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
