package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Configuração do Microsoft Graph (Fase 7). */
public record MicrosoftGraphOptions(
        boolean enabled,
        boolean emailSendEnabled,
        String clientId,
        String tenantId,
        String redirectUri,
        String controlledRecipient,
        URI apiBaseAddress,
        int maximumRetryAttempts,
        long maximumAttachmentBytes) {

    public static final Set<String> DRAFT_SCOPES = Set.of("Mail.ReadWrite");
    public static final Set<String> SEND_SCOPES = Set.of("Mail.ReadWrite", "Mail.Send");

    public MicrosoftGraphOptions {
        tenantId = tenantId == null || tenantId.isBlank() ? "organizations" : tenantId.strip();
        redirectUri = redirectUri == null || redirectUri.isBlank() ? "http://localhost" : redirectUri.strip();
        var api = apiBaseAddress == null ? URI.create("https://graph.microsoft.com/v1.0/") : apiBaseAddress;
        apiBaseAddress = api.toString().endsWith("/") ? api : URI.create(api + "/");
        maximumRetryAttempts = Math.clamp(maximumRetryAttempts, 0, 5);
        maximumAttachmentBytes = maximumAttachmentBytes <= 0 ? 25L * 1024 * 1024 : maximumAttachmentBytes;
    }

    public static MicrosoftGraphOptions of(boolean enabled, boolean sendEnabled, String clientId,
            String controlledRecipient) {
        return new MicrosoftGraphOptions(enabled, sendEnabled, clientId, null, null, controlledRecipient, null, 3, 0);
    }

    public MicrosoftGraphOptions withApiBaseAddress(URI api) {
        return new MicrosoftGraphOptions(enabled, emailSendEnabled, clientId, tenantId, redirectUri,
                controlledRecipient, api, maximumRetryAttempts, maximumAttachmentBytes);
    }

    public Set<String> scopes(boolean send) {
        return send ? SEND_SCOPES : DRAFT_SCOPES;
    }

    public boolean isConfigured() {
        if (!enabled || !isGuid(clientId) || tenantId.isBlank() || !ProviderSupport.isEmail(controlledRecipient)) {
            return false;
        }
        try {
            var redirect = URI.create(redirectUri);
            var scheme = redirect.getScheme() == null ? "" : redirect.getScheme().toLowerCase(Locale.ROOT);
            var host = redirect.getHost() == null ? "" : redirect.getHost().toLowerCase(Locale.ROOT);
            var loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            return loopback && (scheme.equals("http") || scheme.equals("https")) && secureApi();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean secureApi() {
        var scheme = apiBaseAddress.getScheme() == null ? "" : apiBaseAddress.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") || (scheme.equals("http") && "127.0.0.1".equals(apiBaseAddress.getHost())
                && Boolean.getBoolean("folhas.test.allowLoopbackProviders"));
    }

    private static boolean isGuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value.strip());
            return value.strip().length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
