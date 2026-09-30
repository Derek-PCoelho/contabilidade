package br.com.contadoresassociados.folhas.infrastructure.remote;

import br.com.contadoresassociados.folhas.application.dispatch.RemoteEmailSendGuard;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightBatchRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightBatchResponse;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightResponse;
import java.util.List;
import java.util.UUID;

/**
 * Preflight central do envio ({@code POST api/email-dispatch/preflight}).
 *
 * <p>Pendência 4.7: {@link #authorizeBatch} usa {@code api/email-dispatch/preflight/batch} numa
 * única chamada; se o servidor ainda não tiver o endpoint (404/405), cai para chamadas
 * individuais. 401/403 viram resposta negada {@code REMOTE_SEND_FORBIDDEN} (como no .NET); os
 * demais erros propagam e o workflow bloqueia a operação (fail-closed).
 */
public final class HttpRemoteEmailSendGuard implements RemoteEmailSendGuard {

    static final String PATH = "api/email-dispatch/preflight";
    static final String BATCH_PATH = "api/email-dispatch/preflight/batch";

    private final CentralApiClient api;

    public HttpRemoteEmailSendGuard(CentralApiClient api) {
        this.api = api;
    }

    @Override
    public EmailSendPreflightResponse authorize(EmailSendPreflightRequest request) {
        var response = api.postJson(PATH, request);
        if (response.status() == 401 || response.status() == 403) {
            return forbidden(request);
        }
        if (!response.ok()) {
            throw new CentralApiClient.CentralApiException("O preflight central falhou.", response.status(), null);
        }
        return response.read(EmailSendPreflightResponse.class);
    }

    @Override
    public List<EmailSendPreflightResponse> authorizeBatch(List<EmailSendPreflightRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() == 1) {
            return List.of(authorize(requests.getFirst()));
        }
        var response = api.postJson(BATCH_PATH, new EmailSendPreflightBatchRequest(requests));
        if (response.status() == 404 || response.status() == 405) {
            return requests.stream().map(this::authorize).toList();
        }
        if (response.status() == 401 || response.status() == 403) {
            return requests.stream().map(HttpRemoteEmailSendGuard::forbidden).toList();
        }
        if (!response.ok()) {
            throw new CentralApiClient.CentralApiException("O preflight central falhou.", response.status(), null);
        }
        return response.read(EmailSendPreflightBatchResponse.class).results();
    }

    private static EmailSendPreflightResponse forbidden(EmailSendPreflightRequest request) {
        return new EmailSendPreflightResponse(request.operationId(), false, false, AppVersion.CURRENT.toString(),
                UUID.randomUUID(), "REMOTE_SEND_FORBIDDEN");
    }
}
