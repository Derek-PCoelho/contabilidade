package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Configuração do Gmail (Fase 8).
 *
 * <p>Pendência 5.2: {@code clientSecret} — o Google exige o segredo do cliente "Desktop app" na
 * troca de código e no refresh. Ele não é um segredo real em app instalado, mas nunca fica no
 * arquivo de configuração: é lido do cofre do sistema ({@link #CLIENT_SECRET_KEY}) quando ausente.
 *
 * <p>Pendência 5.5: anexos acima de {@code resumableThresholdBytes} usam upload resumable.
 *
 * <p>Pendência 5.3: a busca em "Enviados" ({@code in:sent rfc822msgid:}) exige um escopo de
 * leitura que o {@code gmail.compose} não cobre. {@code sentVerificationScope} (ex.:
 * {@link #READONLY_SCOPE}) é pedido no consentimento quando configurado; sem ele o provedor
 * mantém o comportamento anterior (somente rascunhos) e a reconciliação continua ambígua.
 */
public record GmailOptions(
        boolean enabled,
        boolean emailSendEnabled,
        String clientId,
        String clientSecret,
        String controlledRecipient,
        URI authorizationEndpoint,
        URI tokenEndpoint,
        URI revocationEndpoint,
        URI apiBaseAddress,
        URI uploadBaseAddress,
        int maximumRetryAttempts,
        long maximumAttachmentBytes,
        long resumableThresholdBytes,
        String sentVerificationScope) {

    public static final String COMPOSE_SCOPE = "https://www.googleapis.com/auth/gmail.compose";
    public static final String CLIENT_SECRET_KEY = "email-provider/google-gmail/client-secret";
    public static final String READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String MODIFY_SCOPE = "https://www.googleapis.com/auth/gmail.modify";

    public GmailOptions {
        authorizationEndpoint = orDefault(authorizationEndpoint, "https://accounts.google.com/o/oauth2/v2/auth");
        tokenEndpoint = orDefault(tokenEndpoint, "https://oauth2.googleapis.com/token");
        revocationEndpoint = orDefault(revocationEndpoint, "https://oauth2.googleapis.com/revoke");
        apiBaseAddress = orDefault(apiBaseAddress, "https://gmail.googleapis.com/gmail/v1/");
        uploadBaseAddress = orDefault(uploadBaseAddress, "https://gmail.googleapis.com/upload/gmail/v1/");
        maximumRetryAttempts = Math.clamp(maximumRetryAttempts, 0, 5);
        maximumAttachmentBytes = maximumAttachmentBytes <= 0 ? 25L * 1024 * 1024 : maximumAttachmentBytes;
        resumableThresholdBytes = resumableThresholdBytes <= 0 ? 5L * 1024 * 1024 : resumableThresholdBytes;
        sentVerificationScope = sentVerificationScope == null || sentVerificationScope.isBlank() ? null
                : sentVerificationScope.strip();
        if (sentVerificationScope != null && !List.of(READONLY_SCOPE, MODIFY_SCOPE).contains(sentVerificationScope)) {
            throw new IllegalArgumentException("Escopo de verificação de enviados não suportado.");
        }
    }

    /** Escopos pedidos no consentimento. */
    public List<String> scopes() {
        return sentVerificationScope == null ? List.of(COMPOSE_SCOPE) : List.of(COMPOSE_SCOPE, sentVerificationScope);
    }

    public boolean sentVerificationEnabled() {
        return sentVerificationScope != null;
    }

    public static GmailOptions of(boolean enabled, boolean sendEnabled, String clientId, String controlledRecipient) {
        return new GmailOptions(enabled, sendEnabled, clientId, null, controlledRecipient, null, null, null, null, null,
                3, 0, 0, null);
    }

    public GmailOptions withEndpoints(URI authorization, URI token, URI revocation, URI api, URI upload) {
        return new GmailOptions(enabled, emailSendEnabled, clientId, clientSecret, controlledRecipient, authorization,
                token, revocation, api, upload, maximumRetryAttempts, maximumAttachmentBytes, resumableThresholdBytes,
                sentVerificationScope);
    }

    public GmailOptions withClientSecret(String secret) {
        return new GmailOptions(enabled, emailSendEnabled, clientId, secret, controlledRecipient, authorizationEndpoint,
                tokenEndpoint, revocationEndpoint, apiBaseAddress, uploadBaseAddress, maximumRetryAttempts,
                maximumAttachmentBytes, resumableThresholdBytes, sentVerificationScope);
    }

    public GmailOptions withSentVerificationScope(String scope) {
        return new GmailOptions(enabled, emailSendEnabled, clientId, clientSecret, controlledRecipient,
                authorizationEndpoint, tokenEndpoint, revocationEndpoint, apiBaseAddress, uploadBaseAddress,
                maximumRetryAttempts, maximumAttachmentBytes, resumableThresholdBytes, scope);
    }

    public GmailOptions withResumableThreshold(long bytes) {
        return new GmailOptions(enabled, emailSendEnabled, clientId, clientSecret, controlledRecipient,
                authorizationEndpoint, tokenEndpoint, revocationEndpoint, apiBaseAddress, uploadBaseAddress,
                maximumRetryAttempts, maximumAttachmentBytes, bytes, sentVerificationScope);
    }

    public boolean isConfigured() {
        return enabled
                && clientId != null && clientId.strip().endsWith(".apps.googleusercontent.com")
                && ProviderSupport.isEmail(controlledRecipient)
                && secure(authorizationEndpoint) && secure(tokenEndpoint) && secure(revocationEndpoint)
                && secure(apiBaseAddress) && secure(uploadBaseAddress);
    }

    /** HTTPS obrigatório; {@code http://127.0.0.1} só é aceito para testes com servidor local. */
    private static boolean secure(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getScheme() == null) {
            return false;
        }
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || (scheme.equals("http") && "127.0.0.1".equals(uri.getHost())
                && Boolean.getBoolean("folhas.test.allowLoopbackProviders"));
    }

    private static URI orDefault(URI value, String fallback) {
        var uri = value == null ? URI.create(fallback) : value;
        var text = uri.toString();
        // endereços base precisam de "/" final para resolve() relativo funcionar
        return fallback.endsWith("/") && !text.endsWith("/") ? URI.create(text + "/") : uri;
    }
}
