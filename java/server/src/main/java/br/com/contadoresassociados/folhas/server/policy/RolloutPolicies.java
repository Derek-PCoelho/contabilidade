package br.com.contadoresassociados.folhas.server.policy;

import br.com.contadoresassociados.folhas.application.production.ProductionRollout;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.server.config.ServerSettings;
import java.util.Locale;

/**
 * Leitura das políticas de envio, piloto e produção a partir da configuração (mesmas chaves das
 * seções {@code Phase7}..{@code Phase12} do .NET). Pendência 4.18: tudo desligado por padrão.
 */
public final class RolloutPolicies {

    public static final String GRAPH = "microsoft.graph";
    public static final String GMAIL = "google.gmail";

    private final ServerSettings settings;

    public RolloutPolicies(ServerSettings settings) {
        this.settings = settings;
    }

    public String currentVersion() {
        return AppVersion.CURRENT.toString();
    }

    public String minimumSupportedVersion() {
        return settings.text("phase10.minimum-supported-version", currentVersion());
    }

    public boolean globalSendEnabled() {
        return settings.flag("phase10.email-send-enabled");
    }

    public boolean betaChannelEnabled() {
        return settings.flag("phase10.beta-channel-enabled");
    }

    public boolean stableChannelEnabled() {
        return settings.flag("phase10.stable-channel-enabled");
    }

    public boolean providerSendEnabled(String providerKey) {
        return GMAIL.equals(providerKey) ? settings.flag("phase8.gmail.email-send-enabled")
                : settings.flag("phase7.microsoft-graph.email-send-enabled");
    }

    public String providerMinimumVersion(String providerKey) {
        return GMAIL.equals(providerKey) ? settings.text("phase8.minimum-send-version", currentVersion())
                : settings.text("phase7.minimum-send-version", currentVersion());
    }

    public record Pilot(boolean enabled, String environmentName, boolean allowTest, boolean allowDraft,
            boolean allowSend, boolean requireNonProductionData, int maximumClients) {
    }

    public Pilot pilot() {
        return new Pilot(settings.flag("phase11.enabled"), settings.text("phase11.environment-name", ""),
                settings.flag("phase11.allow-test"), settings.flag("phase11.allow-draft"),
                settings.flag("phase11.allow-send"), settings.flag("phase11.require-non-production-data"),
                settings.integer("phase11.maximum-clients", 0));
    }

    public ProductionRollout.Options production() {
        var stage = switch (settings.text("phase12.stage", "Closed").toLowerCase(Locale.ROOT)) {
            case "limited" -> ProductionRollout.Stage.LIMITED;
            case "gradual" -> ProductionRollout.Stage.GRADUAL;
            default -> ProductionRollout.Stage.CLOSED;
        };
        var roles = settings.list("phase12.allowed-roles");
        if (roles.isEmpty()) {
            roles = ProductionRollout.Options.closed().allowedRoles();
        }
        return new ProductionRollout.Options(true, settings.flag("phase12.enabled"),
                settings.text("phase12.environment-name", "production"), stage, settings.flag("phase12.pilot-approved"),
                settings.flag("phase12.allow-send"),
                settings.flag("phase7.microsoft-graph.email-send-enabled") || settings.flag("phase8.gmail.email-send-enabled"),
                settings.flag("phase12.stable-release-approved"), settings.flag("phase12.backup-restore-drill-completed"),
                settings.flag("phase12.monitoring-ready"), settings.flag("phase12.incident-response-ready"),
                settings.flag("phase12.support-ready"), settings.integer("phase12.maximum-batch-size", 5),
                settings.integer("phase12.maximum-daily-sends", 20),
                settings.text("phase12.minimum-application-version", currentVersion()), roles);
    }

    public ProductionRollout.Readiness productionReadiness() {
        return ProductionRollout.evaluate(production(), pilot().enabled(), globalSendEnabled(), stableChannelEnabled());
    }
}
