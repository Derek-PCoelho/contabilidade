package br.com.contadoresassociados.folhas.desktop;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness;
import br.com.contadoresassociados.folhas.application.production.ProductionRollout;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Piloto supervisionado (Phase11) e produção gradual (Phase12) do Desktop — mesmas chaves e
 * padrões de {@code DesktopPhase2ServiceCollectionExtensions} (.NET). Tudo fica desligado/fechado
 * quando nada é configurado (pendência 4.18).
 *
 * <p>Cada chave {@code SecaoX:Chave} é lida de variável de ambiente em dois formatos:
 * {@code FOLHAS_SECAOX_CHAVE} (preferido) ou {@code SecaoX__Chave} (o mesmo nome que a versão
 * .NET aceitava, para instalações existentes).
 */
public record DesktopRollout(PilotReadiness.Options pilot, ProductionRollout.Options production,
        ProductionRollout.Readiness readiness, boolean emailSendEnabled) {

    public static DesktopRollout fromEnvironment() {
        return from(DesktopRollout::environmentSetting);
    }

    /** Leitura a partir de uma fonte qualquer (testes). */
    public static DesktopRollout from(Function<String, Optional<String>> source) {
        var s = new Settings(source);
        var pilot = new PilotReadiness.Options(s.flag("Phase11:Enabled", false),
                s.text("Phase11:EnvironmentName").orElse("staging"), s.flag("Phase11:AllowTest", true),
                s.flag("Phase11:AllowDraft", true), s.flag("Phase11:AllowSend", false),
                s.flag("Phase11:RequireNonProductionData", true), s.number("Phase11:MaximumClients", 5));
        var closed = ProductionRollout.Options.closed();
        var roles = s.text("Phase12:AllowedRoles")
                .map(v -> Arrays.stream(v.split("[,;]")).map(String::trim).filter(r -> !r.isEmpty())
                        .collect(Collectors.toSet()))
                .filter(set -> !set.isEmpty()).orElse(closed.allowedRoles());
        var production = new ProductionRollout.Options(true, s.flag("Phase12:Enabled", false),
                s.text("Phase12:EnvironmentName").orElse("production"),
                s.text("Phase12:Stage").flatMap(DesktopRollout::stage).orElse(ProductionRollout.Stage.CLOSED),
                s.flag("Phase12:PilotApproved", false), s.flag("Phase12:AllowSend", false),
                s.flag("Phase7:MicrosoftGraph:EmailSendEnabled", false) || s.flag("Phase8:Gmail:EmailSendEnabled", false),
                s.flag("Phase12:StableReleaseApproved", false), s.flag("Phase12:BackupRestoreDrillCompleted", false),
                s.flag("Phase12:MonitoringReady", false), s.flag("Phase12:IncidentResponseReady", false),
                s.flag("Phase12:SupportReady", false), s.number("Phase12:MaximumBatchSize", 5),
                s.number("Phase12:MaximumDailySends", 20),
                s.text("Phase12:MinimumApplicationVersion").orElse(AppVersion.CURRENT.toString()), roles);
        var readiness = ProductionRollout.evaluate(production, pilot.enabled(), s.flag("Phase10:EmailSendEnabled", false),
                s.flag("Phase10:StableChannelEnabled", false));
        return new DesktopRollout(pilot, production, readiness, s.flag("Phase6:EmailSendEnabled", false));
    }

    /** Aplica as travas do piloto e da produção ao despacho (mesmos campos da versão .NET). */
    public DispatchWorkflowOptions applyTo(DispatchWorkflowOptions o) {
        return new DispatchWorkflowOptions(o.providerKey(), o.senderAccountId(), emailSendEnabled, pilot.enabled(),
                pilot.allowTest(), pilot.allowDraft(), pilot.allowSend(), production.enforceForExternalSend(),
                readiness.readyForSend(), production.maximumBatchSize(), o.minimumSendVersion(),
                o.microsoftGraphEnabled(), o.microsoftGraphSendEnabled(), o.microsoftGraphControlledRecipient(),
                o.gmailEnabled(), o.gmailSendEnabled(), o.gmailControlledRecipient(), o.officeName(),
                o.maximumAttachmentBytes());
    }

    /** O painel de produção só aparece quando a proteção está ativa e a etapa não está fechada. */
    public boolean productionPanelVisible() {
        return production.enforceForExternalSend() && production.stage() != ProductionRollout.Stage.CLOSED;
    }

    private static Optional<ProductionRollout.Stage> stage(String value) {
        return Arrays.stream(ProductionRollout.Stage.values())
                .filter(st -> st.name().equalsIgnoreCase(value.trim())).findFirst();
    }

    static Optional<String> environmentSetting(String key) {
        var preferred = "FOLHAS_" + key.replace(":", "_").toUpperCase(Locale.ROOT);
        return DesktopEnvironment.env(preferred).or(() -> DesktopEnvironment.env(key.replace(":", "__")));
    }

    private record Settings(Function<String, Optional<String>> source) {
        Optional<String> text(String key) {
            return source.apply(key).map(String::trim).filter(v -> !v.isEmpty());
        }

        /** Como {@code bool.TryParse}: só "true"/"false"; outro valor mantém o padrão. */
        boolean flag(String key, boolean fallback) {
            return text(key).map(v -> v.equalsIgnoreCase("true") ? Boolean.TRUE
                    : v.equalsIgnoreCase("false") ? Boolean.FALSE : null).orElse(fallback);
        }

        int number(String key, int fallback) {
            return text(key).map(v -> {
                try {
                    return Integer.valueOf(v);
                } catch (NumberFormatException e) {
                    return null;
                }
            }).orElse(fallback);
        }
    }
}
