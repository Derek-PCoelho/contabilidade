package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.server.config.ServerSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Primeiro filtro de toda requisição: correlation id, endereço real (4.11), hosts permitidos,
 * HTTPS/HSTS fora de Development e cabeçalhos de segurança.
 *
 * <p>Pendência 4.2: o CSP das páginas {@code /account} e {@code /connect} libera
 * {@code form-action} para o loopback do Desktop, senão o navegador bloqueava o redirecionamento
 * final do login. Pendência 4.15: {@code Cache-Control: no-store} também em {@code /api/*}.
 */
public final class EdgeFilter extends OncePerRequestFilter {

    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final String PAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; "
            + "form-action 'self' http://127.0.0.1:* http://localhost:* com.danziatus.folhasdamichelly:; "
            + "frame-ancestors 'none'; base-uri 'none'";

    private final ServerSettings settings;

    public EdgeFilter(ServerSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var correlationId = RequestContext.normalizeCorrelationId(request.getHeader(RequestContext.CORRELATION_HEADER));
        request.setAttribute(RequestContext.CORRELATION_ID, correlationId);
        MDC.put("correlationId", correlationId);
        try {
            ClientAddress.resolve(request, settings.knownProxies());
            response.setHeader(RequestContext.CORRELATION_HEADER, correlationId);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            var path = request.getRequestURI();
            var page = path.startsWith("/account") || path.startsWith("/connect");
            response.setHeader("Content-Security-Policy", page ? PAGE_CSP : API_CSP);
            if (page || path.startsWith("/api/")) {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Pragma", "no-cache");
            }
            if (!settings.isDevelopmentOrTesting()) {
                if (!hostAllowed(request)) {
                    Problems.write(request, response, 400, "host.invalid", "Host inválido", "Host não autorizado.");
                    return;
                }
                if (!ClientAddress.secure(request) && !path.startsWith("/health/")) {
                    Problems.write(request, response, 400, "https.required", "HTTPS obrigatório",
                            "Use HTTPS para acessar este servidor.");
                    return;
                }
                response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private boolean hostAllowed(HttpServletRequest request) {
        var allowed = settings.allowedHosts();
        if (allowed.isEmpty()) {
            return false;
        }
        var host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(h -> h.equalsIgnoreCase(host));
    }
}
