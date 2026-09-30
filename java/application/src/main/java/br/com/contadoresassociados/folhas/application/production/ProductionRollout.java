package br.com.contadoresassociados.folhas.application.production;

import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.domain.identity.AppRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Produção gradual: todos os controles precisam estar aprovados antes do envio real. */
public final class ProductionRollout {

    private ProductionRollout() {
    }

    public enum Stage { CLOSED, LIMITED, GRADUAL }

    public record Options(boolean enforceForExternalSend, boolean enabled, String environmentName, Stage stage,
            boolean pilotApproved, boolean allowSend, boolean externalProviderSendEnabled,
            boolean stableReleaseApproved, boolean backupRestoreDrillCompleted, boolean monitoringReady,
            boolean incidentResponseReady, boolean supportReady, int maximumBatchSize, int maximumDailySends,
            String minimumApplicationVersion, Set<String> allowedRoles) {

        public Options {
            allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
        }

        public static Options closed() {
            return new Options(true, false, "production", Stage.CLOSED, false, false, false, false, false, false,
                    false, false, 5, 20, "1.0.0", Set.of(AppRole.OWNER_TECHNICAL.wireName(),
                            AppRole.ADMINISTRATOR.wireName(), AppRole.MANAGER.wireName()));
        }
    }

    public record Readiness(boolean readyForSend, Stage stage, int maximumBatchSize, int maximumDailySends,
            String minimumApplicationVersion, List<String> allowedRoles, List<String> blockers) {
    }

    public static Readiness evaluate(Options o, boolean pilotModeEnabled, boolean globalSendEnabled,
            boolean stableChannelEnabled) {
        var b = new ArrayList<String>();
        add(b, !o.enforceForExternalSend(), "A proteção obrigatória da produção gradual está desativada.");
        add(b, !o.enabled(), "A produção gradual ainda não foi habilitada.");
        add(b, o.environmentName() == null || !o.environmentName().toLowerCase(Locale.ROOT).equals("production"),
                "O ambiente precisa estar identificado como produção.");
        add(b, o.stage() == null || o.stage() == Stage.CLOSED, "A etapa de produção permanece fechada.");
        add(b, pilotModeEnabled || !o.pilotApproved(), "O piloto supervisionado ainda não possui aceite operacional formal.");
        add(b, !o.allowSend() || !globalSendEnabled, "O kill switch de envio permanece desligado.");
        add(b, !o.externalProviderSendEnabled(), "Nenhum provedor externo possui envio habilitado.");
        add(b, !o.stableReleaseApproved() || !stableChannelEnabled,
                "A versão stable assinada ainda não foi aprovada e publicada.");
        add(b, !o.backupRestoreDrillCompleted(), "O exercício de backup e restauração ainda não foi concluído.");
        add(b, !o.monitoringReady(), "Monitoramento e alertas operacionais ainda não estão prontos.");
        add(b, !o.incidentResponseReady(), "A resposta a incidentes ainda não foi validada.");
        add(b, !o.supportReady(), "Responsáveis e canal de suporte ainda não foram confirmados.");
        add(b, o.maximumBatchSize() < 1 || o.maximumBatchSize() > 25, "O limite por lote deve ficar entre 1 e 25 mensagens.");
        add(b, o.maximumDailySends() < 1 || o.maximumDailySends() > 500, "O limite diário deve ficar entre 1 e 500 mensagens.");
        var version = AppVersion.parse(o.minimumApplicationVersion());
        add(b, version.isEmpty(), "A versão mínima da produção é inválida.");
        add(b, version.isPresent() && !AppVersion.CURRENT.isAtLeast(version.get()),
                "Esta versão do aplicativo está abaixo do mínimo exigido pela produção.");
        var privileged = Set.of(AppRole.OWNER_TECHNICAL.wireName(), AppRole.ADMINISTRATOR.wireName(),
                AppRole.MANAGER.wireName());
        var allowed = o.allowedRoles().stream().filter(privileged::contains).sorted(Comparator.naturalOrder()).toList();
        add(b, allowed.isEmpty() || !privileged.containsAll(o.allowedRoles()),
                "Os papéis autorizados devem ser somente Gestor, Administrador ou Proprietário técnico.");
        return new Readiness(b.isEmpty(), o.stage(), o.maximumBatchSize(), o.maximumDailySends(),
                o.minimumApplicationVersion(), allowed, List.copyOf(b));
    }

    private static void add(List<String> blockers, boolean condition, String message) {
        if (condition) {
            blockers.add(message);
        }
    }
}
