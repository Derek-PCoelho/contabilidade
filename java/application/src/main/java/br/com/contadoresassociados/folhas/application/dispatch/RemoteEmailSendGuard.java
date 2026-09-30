package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightResponse;
import java.util.List;

/**
 * Preflight central (servidor). Pendência 4.7: {@link #authorizeBatch} autoriza uma sequência
 * inteira numa única chamada, evitando 429 em lotes grandes.
 */
public interface RemoteEmailSendGuard {

    EmailSendPreflightResponse authorize(EmailSendPreflightRequest request);

    default List<EmailSendPreflightResponse> authorizeBatch(List<EmailSendPreflightRequest> requests) {
        return requests.stream().map(this::authorize).toList();
    }
}
