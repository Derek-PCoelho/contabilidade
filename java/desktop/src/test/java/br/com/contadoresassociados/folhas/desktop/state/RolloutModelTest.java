package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness.ChecklistKey;
import br.com.contadoresassociados.folhas.desktop.DesktopRollout;
import br.com.contadoresassociados.folhas.desktop.DesktopServices;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Painéis de piloto supervisionado e produção gradual (Configurações → suporte). */
class RolloutModelTest {

    @TempDir
    Path temp;
    private DesktopServices services;
    private final List<String> statuses = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (services != null) {
            services.close();
        }
    }

    private RolloutModel model(Map<String, String> settings) {
        if (services != null) {
            services.close();
        }
        services = DesktopServices.create(temp.resolve("data"), true,
                DesktopRollout.from(key -> Optional.ofNullable(settings.get(key))));
        var shell = new ShellState(2026, 3);
        shell.statusMessage().addListener((obs, old, now) -> statuses.add(now));
        var tasks = UiTasks.synchronous();
        var documents = new DocumentsModel(services.review(), services.recognition(), shell, tasks,
                services.documentArchiveDirectory());
        var dispatch = new DispatchModel(services.dispatch(), services.emailConnection(), documents, shell, tasks);
        return new RolloutModel(services.rollout(), services.pilot(), services.incidents(), services.catalog(), documents,
                dispatch, shell, tasks);
    }

    @Test
    void panelsStayHiddenByDefault() {
        var m = model(Map.of());
        assertThat(m.pilotMode()).isFalse();
        assertThat(m.productionVisible()).isFalse();
        m.saveChecklist();
        assertThat(statuses).last().isEqualTo("O checklist do piloto não está disponível nesta instalação.");
    }

    @Test
    void checklistIsSavedAuditedAndReloadedByTheLocalOperator() {
        var m = model(Map.of("Phase11:Enabled", "true"));
        m.refresh();
        assertThat(m.checklistProgress.get()).isZero();
        assertThat(m.pilotStatusTitle.get()).isEqualTo("Em preparação");
        assertThat(m.pilotMetricsSummary.get()).isEqualTo("0 documento(s) • 0 teste(s) • 0 rascunho(s) • 0 pendência(s) técnica(s)");

        m.checklist.get(ChecklistKey.NON_PRODUCTION_DATA_CONFIRMED).set(true);
        m.checklist.get(ChecklistKey.WINDOWS_STATION_VALIDATED).set(true);
        m.checklist.get(ChecklistKey.MAC_OS_STATION_VALIDATED).set(true);
        m.saveChecklist();
        assertThat(statuses).last().isEqualTo("Checklist salvo. Os itens pendentes continuam bloqueando o início do piloto.");
        assertThat(m.checklistProgress.get()).isEqualTo(50);
        assertThat(m.pilotStatusSummary.get()).isEqualTo("Faltam 3 confirmações no checklist operacional.");

        // Nova instância: o checklist volta do SQLite, com auditoria de quem confirmou.
        var reloaded = new RolloutModel(services.rollout(), services.pilot(), services.incidents(), services.catalog(),
                new DocumentsModel(services.review(), services.recognition(), new ShellState(2026, 3), UiTasks.synchronous(),
                        services.documentArchiveDirectory()),
                new DispatchModel(services.dispatch(), services.emailConnection(),
                        new DocumentsModel(services.review(), services.recognition(), new ShellState(2026, 3),
                                UiTasks.synchronous(), services.documentArchiveDirectory()),
                        new ShellState(2026, 3), UiTasks.synchronous()),
                new ShellState(2026, 3), UiTasks.synchronous());
        reloaded.refresh();
        assertThat(reloaded.checklist.get(ChecklistKey.WINDOWS_STATION_VALIDATED).get()).isTrue();
        assertThat(reloaded.checklist.get(ChecklistKey.ROLLBACK_VALIDATED).get()).isFalse();
        var snapshot = services.pilot().load(PilotReadiness.OperationalMetrics.EMPTY);
        assertThat(snapshot.checklist().auditEvents()).hasSize(3)
                .allMatch(e -> DesktopServices.LOCAL_ACTOR_ID.equals(e.actorId()));

        for (var key : ChecklistKey.values()) {
            m.checklist.get(key).set(true);
        }
        m.saveChecklist();
        assertThat(m.checklistProgress.get()).isEqualTo(100);
        assertThat(m.pilotReady.get()).isTrue();
        assertThat(m.pilotStatusTitle.get()).isEqualTo("Pronto para supervisão");
    }

    @Test
    void productionPanelIsInformativeAndListsWhatIsMissing() {
        var m = model(Map.of("Phase12:Stage", "Limited", "Phase12:Enabled", "true", "Phase12:StableReleaseApproved", "true"));
        assertThat(m.productionVisible()).isTrue();
        assertThat(m.productionReady()).isFalse();
        assertThat(m.productionStageLabel()).isEqualTo("Etapa limitada");
        assertThat(m.productionStatusTitle()).isEqualTo("Produção bloqueada");
        assertThat(m.productionGates()).containsExactly("○ Piloto aceito formalmente", "✓ Stable assinada e aprovada",
                "○ Backup e restauração exercitados", "○ Monitoramento e alertas prontos",
                "○ Resposta a incidentes validada", "○ Suporte e responsáveis confirmados");
        assertThat(m.productionBlockers()).isNotEmpty().first().isEqualTo(m.productionStatusSummary());
        assertThat(m.productionLimitsSummary()).isEqualTo("Até 5 mensagem(ns) por sequência e 20 por dia, com contagem central por organização.");
    }
}
