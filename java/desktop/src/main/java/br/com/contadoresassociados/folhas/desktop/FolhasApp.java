package br.com.contadoresassociados.folhas.desktop;

import br.com.contadoresassociados.folhas.desktop.sections.HomeSection;
import br.com.contadoresassociados.folhas.desktop.sections.PendingSection;
import br.com.contadoresassociados.folhas.desktop.sections.Section;
import br.com.contadoresassociados.folhas.desktop.state.AppSection;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import java.time.ZonedDateTime;
import java.util.Locale;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aplicação JavaFX — janela 1360×860 (mínimo 960×640), tema claro, mesma identidade visual. */
public final class FolhasApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(FolhasApp.class);
    private DesktopServices services;

    @Override
    public void init() {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"));
        services = DesktopServices.create(DesktopEnvironment.dataDirectory());
    }

    @Override
    public void start(Stage stage) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> LOG.error("Falha inesperada na interface.", error));
        var today = ZonedDateTime.now(br.com.contadoresassociados.folhas.application.common.Clock.BRAZIL);
        var state = new ShellState(today.getYear(), today.getMonthValue());
        if (services.startupNotice() != null) {
            state.status(services.startupNotice());
        }
        var window = new MainWindow(state, section -> createSection(section, state));
        var scene = new Scene(window, 1360, 860);
        scene.getStylesheets().add(FolhasApp.class.getResource("theme.css").toExternalForm());
        stage.setTitle("Folhas da Michelly");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        var icon = FolhasApp.class.getResourceAsStream("branding/logo1.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon));
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> Platform.exit());
        if ("true".equalsIgnoreCase(System.getenv("FOLHAS_DESKTOP_MAXIMIZED"))) {
            stage.setMaximized(true);
        }
        stage.show();
    }

    private Section createSection(AppSection section, ShellState state) {
        return switch (section) {
            case HOME -> new HomeSection(state, () -> state.show(AppSection.CLIENTS));
            default -> new PendingSection(section.title(), section.description());
        };
    }

    @Override
    public void stop() {
        if (services != null) {
            services.close();
        }
    }
}
