package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/** Atributos por requisição (usuária autenticada e correlation id). */
public final class RequestContext {

    public static final String PRINCIPAL = "folhas.principal";
    public static final String CORRELATION_ID = "folhas.correlationId";
    public static final String CORRELATION_HEADER = "X-Correlation-ID";

    private RequestContext() {
    }

    public static Optional<Principal> principal(HttpServletRequest request) {
        return Optional.ofNullable((Principal) request.getAttribute(PRINCIPAL));
    }

    public static Principal require(HttpServletRequest request) {
        return principal(request).orElseThrow(ApiException::unauthorized);
    }

    public static String correlationId(HttpServletRequest request) {
        var value = request.getAttribute(CORRELATION_ID);
        return value instanceof String s ? s : UUID.randomUUID().toString().replace("-", "");
    }

    /** Mesma regra do .NET: até 64 caracteres [A-Za-z0-9-_.]; senão um novo id. */
    public static String normalizeCorrelationId(String candidate) {
        if (candidate != null && !candidate.isBlank() && candidate.length() <= 64
                && candidate.chars().allMatch(ch -> (ch < 128 && Character.isLetterOrDigit(ch)) || ch == '-' || ch == '_'
                        || ch == '.')) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
