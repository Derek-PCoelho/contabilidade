package br.com.contadoresassociados.folhas.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness.ChecklistKey;
import br.com.contadoresassociados.folhas.desktop.sections.SettingsSection;
import br.com.contadoresassociados.folhas.desktop.state.AccountModel;
import br.com.contadoresassociados.folhas.desktop.state.DispatchModel;
import br.com.contadoresassociados.folhas.desktop.state.DocumentsModel;
import br.com.contadoresassociados.folhas.desktop.state.ReportsModel;
import br.com.contadoresassociados.folhas.desktop.state.RolloutModel;
import br.com.contadoresassociados.folhas.desktop.state.SettingsModel;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Configurações com piloto e produção habilitados, no toolkit JavaFX real. */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class SettingsSectionSmokeTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path temp;

    @BeforeAll
    static void startToolkit() throws Exception {
        var started = new CompletableFuture<Void>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyStarted) {
            started.complete(null);
        }
        started.get(30, TimeUnit.SECONDS);
    }

    @Test
    void pilotAndProductionPanelsRenderAndTheChecklistProgressFills() throws Exception {
        var result = new CompletableFuture<String>();
        Platform.runLater(() -> {
            var settings = Map.of("Phase11:Enabled", "true", "Phase12:Enabled", "true", "Phase12:Stage", "Limited");
            try (var services = DesktopServices.create(temp.resolve("data"), true,
                    DesktopRollout.from(key -> Optional.ofNullable(settings.get(key))))) {
                var shell = new ShellState(2026, 9);
                var tasks = UiTasks.synchronous();
                var documents = new DocumentsModel(services.review(), services.recognition(), shell, tasks,
                        services.documentArchiveDirectory());
                var dispatch = new DispatchModel(services.dispatch(), services.emailConnection(), documents, shell, tasks);
                var reports = new ReportsModel(services.dispatch(), services.catalog(), dispatch, documents, shell, tasks,
                        temp.resolve("relatorios"), 2026, 9);
                var model = new SettingsModel(services.emailConnection(), services.catalog(), services.backup(),
                        services.updates(), services.preferences(), documents, reports, dispatch, shell, tasks, services.clock());
                var account = new AccountModel(services.oidc(), shell, tasks, java.time.ZoneId.systemDefault());
                var rollout = new RolloutModel(services.rollout(), services.pilot(), services.incidents(), services.catalog(),
                        documents, dispatch, shell, tasks);
                var section = new SettingsSection(model, account, rollout);
                var scene = new Scene((Parent) section.node(), 1200, 900);
                scene.getStylesheets().add(FolhasApp.class.getResource("theme.css").toExternalForm());
                section.onShown();
                // Abre todos os painéis recolhidos (suporte, produção e piloto).
                for (var i = 0; i < 3; i++) {
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    scene.getRoot().lookupAll(".titled-pane").forEach(n -> ((TitledPane) n).setExpanded(true));
                }
                for (var key : ChecklistKey.values()) {
                    rollout.checklist.get(key).set(key.ordinal() < 3);
                }
                rollout.saveChecklist();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var bar = scene.getRoot().lookupAll(".progress-bar").stream().map(ProgressBar.class::cast)
                        .filter(p -> "Progresso do checklist de homologação".equals(p.getAccessibleText())).findFirst()
                        .orElseThrow();
                Node fill = bar.lookup(".bar");
                // Pixels reais: a parte preenchida precisa aparecer (o tema já escondeu o preenchimento das barras finas).
                var image = bar.snapshot(null, null);
                var painted = image.getPixelReader().getColor((int) (image.getWidth() * 0.25), (int) (image.getHeight() / 2))
                        .equals(javafx.scene.paint.Color.web("#9B7114"));
                var titles = scene.getRoot().lookupAll(".titled-pane").stream().filter(Node::isVisible)
                        .map(n -> ((TitledPane) n).getText()).toList();
                result.complete(rollout.checklistProgress.get() + "|" + bar.getProgress() + "|"
                        + (fill.getLayoutBounds().getWidth() > bar.getWidth() * 0.4 && painted) + "|" + titles);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("50|0.5|true|[Quando Server, PostgreSQL e login são necessários?, "
                + "Detalhes técnicos para suporte, Produção gradual (uso exclusivo do suporte), "
                + "Piloto supervisionado (uso exclusivo do suporte)]");
    }
}
