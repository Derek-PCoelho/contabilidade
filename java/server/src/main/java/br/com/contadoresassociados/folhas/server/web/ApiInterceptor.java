package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Autorização declarativa dos endpoints ({@link Endpoint}). Ordem igual ao pipeline .NET:
 * autenticação → limite de taxa (partição por usuária ou IP real) → permissões/MFA/sessão.
 */
public final class ApiInterceptor implements HandlerInterceptor {

    private final ApiAuthenticator authenticator;
    private final RateLimiter limiter;

    public ApiInterceptor(ApiAuthenticator authenticator, RateLimiter limiter) {
        this.authenticator = authenticator;
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var endpoint = method.getMethodAnnotation(Endpoint.class);
        if (endpoint == null) {
            endpoint = method.getBeanType().getAnnotation(Endpoint.class);
        }
        var principal = authenticator.authenticate(request);
        principal.ifPresent(p -> request.setAttribute(RequestContext.PRINCIPAL, p));
        var partition = principal.map(p -> p.userId().toString()).orElseGet(() -> ClientAddress.of(request));
        if (!rate(request, response, RateLimiter.Policy.GLOBAL, partition)) {
            return false;
        }
        if (endpoint == null) {
            // negação por padrão: todo controlador precisa declarar sua política
            Problems.write(request, response, 403, "endpoint.unprotected", Problems.title(403),
                    "Endpoint sem política de acesso.");
            return false;
        }
        var ratePartition = endpoint.rate() == RateLimiter.Policy.AUTHENTICATION ? ClientAddress.of(request) : partition;
        if (!rate(request, response, endpoint.rate(), ratePartition)) {
            return false;
        }
        if (endpoint.anonymous()) {
            return true;
        }
        if (principal.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            Problems.write(request, response, 401, "authentication_required", Problems.title(401),
                    "Entre novamente para continuar.");
            return false;
        }
        var p = principal.get();
        for (var permission : endpoint.permissions()) {
            if (!p.has(permission)) {
                Problems.write(request, response, 403, "permission_denied", Problems.title(403),
                        "Seu perfil não tem permissão para esta operação.");
                return false;
            }
        }
        if (endpoint.mfa() && !p.mfa()) {
            Problems.write(request, response, 403, "mfa_required", Problems.title(403),
                    "Confirme com o segundo fator (aplicativo autenticador) para continuar.");
            return false;
        }
        if (!authenticator.sessionActive(p)) {
            Problems.write(request, response, 401, "session_revoked", Problems.title(401),
                    "Esta sessão foi encerrada. Entre novamente.");
            return false;
        }
        return true;
    }

    private boolean rate(HttpServletRequest request, HttpServletResponse response, RateLimiter.Policy policy,
            String partition) throws java.io.IOException {
        var retryAfter = limiter.acquire(policy, partition);
        if (retryAfter == 0) {
            return true;
        }
        response.setHeader("Retry-After", Long.toString(retryAfter));
        Problems.write(request, response, 429, "rate_limited", Problems.title(429),
                "Aguarde " + retryAfter + " segundos antes de tentar novamente.");
        return false;
    }
}
