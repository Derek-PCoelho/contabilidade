package br.com.contadoresassociados.folhas.desktop;

import br.com.contadoresassociados.folhas.desktop.sections.ClientsSection;
import br.com.contadoresassociados.folhas.desktop.sections.DispatchSection;
import br.com.contadoresassociados.folhas.desktop.sections.DocumentsSection;
import br.com.contadoresassociados.folhas.desktop.sections.HistorySection;
import br.com.contadoresassociados.folhas.desktop.sections.HomeSection;
import br.com.contadoresassociados.folhas.desktop.sections.PendingSection;
import br.com.contadoresassociados.folhas.desktop.sections.ReportsSection;
import br.com.contadoresassociados.folhas.desktop.sections.Section;
import br.com.contadoresassociados.folhas.desktop.sections.SettingsSection;
import br.com.contadoresassociados.folhas.desktop.state.AppSection;
import br.com.contadoresassociados.folhas.desktop.state.ClientsModel;
import br.com.contadoresassociados.folhas.desktop.state.DispatchModel;
import br.com.contadoresassociados.folhas.desktop.state.DocumentsModel;
import br.com.contadoresassociados.folhas.desktop.state.HistoryModel;
import br.com.contadoresassociados.folhas.desktop.state.ReportsModel;
import br.com.contadoresassociados.folhas.desktop.state.SettingsModel;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
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
    private final UiTasks tasks = UiTasks.background();

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

    private ClientsModel clients;
    private DocumentsModel documents;
    private DispatchModel dispatch;
    private ReportsModel reports;
    private HistoryModel history;
    private SettingsModel settings;
    private br.com.contadoresassociados.folhas.desktop.state.AccountModel account;

    private Section createSection(AppSection section, ShellState state) {
        if (clients == null) {
            documents = new DocumentsModel(services.review(), services.recognition(), state, tasks,
                    services.documentArchiveDirectory());
            // Alterações cadastrais já revalidam a revisão na mesma transação; só recarregamos a tela.
            clients = new ClientsModel(services.catalog(), tasks, state::status, documents::reload);
            documents.onOpenClient(id -> {
                state.show(AppSection.CLIENTS);
                clients.openClient(id);
            });
            documents.onContinueToDispatch(() -> state.show(AppSection.DISPATCH));
            dispatch = new DispatchModel(services.dispatch(), services.emailConnection(), documents, state, tasks);
            dispatch.onShowDocuments(() -> state.show(AppSection.DOCUMENTS));
            dispatch.onShowReports(() -> state.show(AppSection.REPORTS));
            history = new HistoryModel(documents, dispatch, services.catalog(), services.incidents(), services.preferences(),
                    state, tasks, services.clock(), java.time.ZoneId.systemDefault());
            dispatch.onReportIncident(attempt -> {
                history.startIncidentFor(attempt);
                state.show(AppSection.HISTORY);
            });
            var today = java.time.LocalDate.now(br.com.contadoresassociados.folhas.application.common.Clock.BRAZIL);
            reports = new ReportsModel(services.dispatch(), services.catalog(), dispatch, documents, state, tasks,
                    services.reportsDirectory(), today.getYear(), today.getMonthValue());
            settings = new SettingsModel(services.emailConnection(), services.catalog(), services.backup(), services.updates(),
                    services.preferences(), documents, reports, dispatch, state, tasks, services.clock());
            account = new br.com.contadoresassociados.folhas.desktop.state.AccountModel(services.oidc(), state, tasks,
                    java.time.ZoneId.systemDefault());
            // Troca de sessão muda escopo e permissões: recarrega o que depende deles.
            account.onSessionChanged(() -> {
                documents.reload();
                dispatch.reload();
            });
            account.refresh();
            settings.onCatalogChanged(() -> {
                clients.loadClients();
                documents.reload();
            });
            DesktopEnvironment.env("FOLHAS_DESKTOP_INPUT_FOLDER").ifPresent(documents.inputFolder::set);
            documents.loadIfNeeded();
        }
        return switch (section) {
            case HOME -> new HomeSection(state, () -> {
                state.show(AppSection.CLIENTS);
                clients.newClient();
            });
            case CLIENTS -> new ClientsSection(clients);
            case DOCUMENTS -> new DocumentsSection(documents, state);
            case DISPATCH -> new DispatchSection(dispatch);
            case REPORTS -> new ReportsSection(reports);
            case HISTORY -> new HistorySection(history);
            case SETTINGS -> new SettingsSection(settings, account);
            default -> new PendingSection(section.title(), section.description());
        };
    }

    @Override
    public void stop() {
        if (settings != null) {
            settings.onApplicationClosing();
        }
        if (services != null) {
            services.close();
        }
    }
}
