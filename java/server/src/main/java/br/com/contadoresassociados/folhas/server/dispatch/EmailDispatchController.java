package br.com.contadoresassociados.folhas.server.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightBatchRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightBatchResponse;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightRequest;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailSendPreflightResponse;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code api/email-dispatch}: preflight unitário (compatível) e em lote (4.7). */
@RestController
@RequestMapping("/api/email-dispatch")
public class EmailDispatchController {

    private final PreflightService service;

    public EmailDispatchController(PreflightService service) {
        this.service = service;
    }

    @PostMapping("/preflight")
    @Endpoint(permissions = "email.send", mfa = true, rate = Policy.SENSITIVE)
    public EmailSendPreflightResponse preflight(HttpServletRequest http, @RequestBody EmailSendPreflightRequest request) {
        try {
            return service.authorize(RequestContext.require(http), request);
        } catch (PreflightService.InvalidRequest e) {
            throw ApiException.badRequest("PREFLIGHT_INVALID_REQUEST", e.getMessage());
        }
    }

    @PostMapping("/preflight/batch")
    @Endpoint(permissions = "email.send", mfa = true, rate = Policy.SENSITIVE)
    public EmailSendPreflightBatchResponse preflightBatch(HttpServletRequest http,
            @RequestBody EmailSendPreflightBatchRequest request) {
        if (request == null || request.items().isEmpty() || request.items().size() > PreflightService.MAX_BATCH_ITEMS) {
            throw ApiException.badRequest("PREFLIGHT_INVALID_REQUEST",
                    "Envie entre 1 e " + PreflightService.MAX_BATCH_ITEMS + " mensagens por lote.");
        }
        return new EmailSendPreflightBatchResponse(service.authorizeBatch(RequestContext.require(http), request.items()));
    }
}
