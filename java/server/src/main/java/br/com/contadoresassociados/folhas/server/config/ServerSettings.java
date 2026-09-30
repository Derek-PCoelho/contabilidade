package br.com.contadoresassociados.folhas.server.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Configuração do servidor com as mesmas chaves do {@code appsettings.json} da versão .NET
 * (prefixo {@code folhas.}, por exemplo {@code folhas.phase10.email-send-enabled}).
 *
 * <p>Pendência 4.18: todos os interruptores de envio e do piloto nascem desligados.
 */
public final class ServerSettings {

    private final Environment env;

    public ServerSettings(Environment env) {
        this.env = env;
    }

    public String environmentName() {
        return env.getProperty("folhas.environment", "Production");
    }

    public boolean isDevelopment() {
        return "Development".equalsIgnoreCase(environmentName());
    }

    public boolean isTesting() {
        return "Testing".equalsIgnoreCase(environmentName());
    }

    public boolean isDevelopmentOrTesting() {
        return isDevelopment() || isTesting();
    }

    public boolean flag(String key) {
        return env.getProperty("folhas." + key, Boolean.class, false);
    }

    public String text(String key, String fallback) {
        var value = env.getProperty("folhas." + key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public int integer(String key, int fallback) {
        return env.getProperty("folhas." + key, Integer.class, fallback);
    }

    public Set<String> list(String key) {
        var raw = env.getProperty("folhas." + key, "");
        var result = new LinkedHashSet<String>();
        Arrays.stream(raw.split("[,;]")).map(String::strip).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }

    /** Autoridade pública (emissor dos tokens), ex.: {@code https://folhas.exemplo.com.br}. */
    public String issuer() {
        var issuer = text("oidc.issuer", isDevelopmentOrTesting() ? "http://localhost:8080" : null);
        if (issuer == null) {
            throw new IllegalStateException("folhas.oidc.issuer é obrigatório fora de Development/Testing.");
        }
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    public Duration accessTokenLifetime() {
        return Duration.ofMinutes(integer("oidc.access-token-minutes", 10));
    }

    public Duration refreshTokenLifetime() {
        return Duration.ofDays(integer("oidc.refresh-token-days", 30));
    }

    /** Pendência 4.3: o fator MFA herdado pelo refresh expira e exige novo login. */
    public Duration mfaLifetime() {
        return Duration.ofHours(integer("oidc.mfa-hours", 12));
    }

    public Duration loginSessionLifetime() {
        return Duration.ofHours(8);
    }

    /** Pendência 4.11: proxies confiáveis para {@code X-Forwarded-For}/{@code X-Forwarded-Proto}. */
    public List<String> knownProxies() {
        return List.copyOf(list("forwarded-headers.known-proxies"));
    }

    public Set<String> allowedHosts() {
        return list("allowed-hosts");
    }

    public boolean developmentAuthenticationEnabled() {
        return isDevelopmentOrTesting() && flag("authentication.enable-development-scheme");
    }
}
