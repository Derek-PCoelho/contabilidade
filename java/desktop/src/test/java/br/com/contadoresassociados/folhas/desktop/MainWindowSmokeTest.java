package br.com.contadoresassociados.folhas.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.desktop.sections.ClientsSection;
import br.com.contadoresassociados.folhas.desktop.sections.HomeSection;
import br.com.contadoresassociados.folhas.desktop.sections.PendingSection;
import br.com.contadoresassociados.folhas.desktop.state.AppSection;
import br.com.contadoresassociados.folhas.desktop.state.ClientsModel;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import br.com.contadoresassociados.folhas.infrastructure.clients.SqliteLocalClientCatalogService;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Monta a janela completa no toolkit JavaFX real (pega erros de binding/layout que só
 * aparecem em execução). Roda quando há um display — no CI, sob Xvfb.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class MainWindowSmokeTest {

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
    void buildsEverySectionAndNavigates() throws Exception {
        var result = new CompletableFuture<String>();
        Platform.runLater(() -> {
            try (var db = LocalDatabase.inMemory()) {
                var state = new ShellState(2026, 9);
                var clients = new ClientsModel(new SqliteLocalClientCatalogService(db, Clock.system(), null),
                        UiTasks.synchronous(), state::status, null);
                var window = new MainWindow(state, section -> switch (section) {
                    case HOME -> new HomeSection(state, clients::newClient);
                    case CLIENTS -> new ClientsSection(clients);
                    default -> new PendingSection(section.title(), section.description());
                });
                var scene = new Scene(window, 1360, 860);
                scene.getStylesheets().add(FolhasApp.class.getResource("theme.css").toExternalForm());
                window.applyCss();
                window.layout();
                for (var section : AppSection.values()) {
                    state.show(section);
                    window.applyCss();
                    window.layout();
                }
                clients.newClient();
                result.complete(state.workPeriodLabel().get() + "|" + state.sectionTitle().get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("SETEMBRO 2026|Configurações");
    }
}
