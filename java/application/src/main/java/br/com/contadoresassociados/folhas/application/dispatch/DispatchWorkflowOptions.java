package br.com.contadoresassociados.folhas.application.dispatch;

/**
 * Opções do despacho.
 *
 * <p>Pendência 7.5: {@code emailSendEnabled} agora é {@code false} por padrão (fail-closed local).
 * Pendência 5.1: {@code productionRolloutReady} libera o envio aos destinatários reais do
 * cadastro no modo Send; sem ele, provedores reais só enviam ao destinatário controlado.
 */
public record DispatchWorkflowOptions(
        String providerKey,
        String senderAccountId,
        boolean emailSendEnabled,
        boolean pilotModeEnabled,
        boolean pilotAllowTest,
        boolean pilotAllowDraft,
        boolean pilotAllowSend,
        boolean productionRolloutEnforced,
        boolean productionRolloutReady,
        int productionMaximumBatchSize,
        String minimumSendVersion,
        boolean microsoftGraphEnabled,
        boolean microsoftGraphSendEnabled,
        String microsoftGraphControlledRecipient,
        boolean gmailEnabled,
        boolean gmailSendEnabled,
        String gmailControlledRecipient,
        String officeName,
        long maximumAttachmentBytes) {

    public static final String FAKE_PROVIDER = "fake.local";
    public static final String GRAPH_PROVIDER = "microsoft.graph";
    public static final String GMAIL_PROVIDER = "google.gmail";
    public static final String DEFAULT_OFFICE_NAME = "AL Contadores Associados";

    public static DispatchWorkflowOptions defaults() {
        return new DispatchWorkflowOptions(FAKE_PROVIDER, "fake://local", false, false, true, true, false, true, false,
                5, "1.0.0", false, false, null, false, false, null, DEFAULT_OFFICE_NAME, 25L * 1024 * 1024);
    }

    public boolean isExternalProvider() {
        return GRAPH_PROVIDER.equals(providerKey) || GMAIL_PROVIDER.equals(providerKey);
    }

    public String controlledRecipient() {
        return switch (providerKey) {
            case GRAPH_PROVIDER -> microsoftGraphControlledRecipient;
            case GMAIL_PROVIDER -> gmailControlledRecipient;
            default -> null;
        };
    }

    public boolean providerEnabled() {
        return switch (providerKey) {
            case GRAPH_PROVIDER -> microsoftGraphEnabled;
            case GMAIL_PROVIDER -> gmailEnabled;
            default -> true;
        };
    }

    public boolean providerSendEnabled() {
        return switch (providerKey) {
            case GRAPH_PROVIDER -> microsoftGraphSendEnabled;
            case GMAIL_PROVIDER -> gmailSendEnabled;
            default -> emailSendEnabled;
        };
    }

    public String confirmationSuffix() {
        return switch (providerKey) {
            case GRAPH_PROVIDER -> " GRAPH";
            case GMAIL_PROVIDER -> " GMAIL";
            default -> "";
        };
    }

    public DispatchWorkflowOptions withProvider(String key, String sender) {
        return new DispatchWorkflowOptions(key, sender, emailSendEnabled, pilotModeEnabled, pilotAllowTest,
                pilotAllowDraft, pilotAllowSend, productionRolloutEnforced, productionRolloutReady,
                productionMaximumBatchSize, minimumSendVersion, microsoftGraphEnabled, microsoftGraphSendEnabled,
                microsoftGraphControlledRecipient, gmailEnabled, gmailSendEnabled, gmailControlledRecipient, officeName,
                maximumAttachmentBytes);
    }
}
