package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountConnectionService;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences;
import br.com.contadoresassociados.folhas.application.security.ProtectedBackupService;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportResult;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogTransferDocument;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Configurações — conta de e-mail, cópia de segurança protegida, pastas de trabalho,
 * atualizações, revisão de retenção e detalhes técnicos. Porte do trecho de
 * configurações do {@code MainViewModel}; preferências persistem em {@link WorkspacePreferences}.
 */
public final class SettingsModel {

    /** Prazo de revisão do acervo ({@code RetentionOption}). */
    public record RetentionOption(int months, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<RetentionOption> RETENTION_OPTIONS = List.of(new RetentionOption(0, "Sem exclusão automática"),
            new RetentionOption(2, "Revisar itens com mais de 2 meses"), new RetentionOption(3, "Revisar itens com mais de 3 meses"),
            new RetentionOption(6, "Revisar itens com mais de 6 meses"), new RetentionOption(12, "Revisar após 1 ano"),
            new RetentionOption(24, "Revisar após 2 anos"), new RetentionOption(60, "Revisar após 5 anos"),
            new RetentionOption(120, "Revisar após 10 anos"));

    public static final String PHASE = "Fase 12 — Produção gradual";
    public static final String ENVIRONMENT = "Java 25 LTS • JavaFX 25 • Windows + macOS";

    private final EmailAccountConnectionService email;
    private final ClientCatalogService catalog;
    private final ProtectedBackupService backup;
    private final AppUpdates.Service updates;
    private final WorkspacePreferences.Store preferences;
    private final DocumentsModel documents;
    private final ReportsModel reports;
    private final DispatchModel dispatch;
    private final ShellState shell;
    private final UiTasks tasks;
    private final Clock clock;
    private Runnable catalogChanged = () -> { };

    // ---------------------------------------------------------------- e-mail
    public final StringProperty emailStatus = new SimpleStringProperty("Nenhuma conta de e-mail foi conectada nesta instalação.");
    public final StringProperty emailHelp = new SimpleStringProperty(
            "O modo Teste seguro funciona sem conta. Gmail e Outlook serão liberados após o registro oficial desta instalação em cada provedor.");
    public final StringProperty providerKey = new SimpleStringProperty(DispatchWorkflowOptions.FAKE_PROVIDER);
    public final BooleanProperty emailConfigured = new SimpleBooleanProperty(false);
    public final BooleanProperty emailConnected = new SimpleBooleanProperty(false);
    public final BooleanProperty keepEmailSession = new SimpleBooleanProperty(true);
    public final StringProperty technicalDetails = new SimpleStringProperty(
            "Modo protegido ativo. Integrações externas permanecem sujeitas às autorizações e travas de segurança.");

    // ---------------------------------------------------------------- cópia de segurança
    public final StringProperty backupPassword = new SimpleStringProperty("");
    public final StringProperty restoreSummary = new SimpleStringProperty("Nenhuma cópia escolhida para restauração.");
    public final BooleanProperty canApplyRestore = new SimpleBooleanProperty(false);
    private ClientCatalogTransferDocument pendingRestore;

    // ---------------------------------------------------------------- pastas
    public final StringProperty reportOutputDirectory;
    public final ObjectProperty<Path> archiveDirectory;
    public final StringProperty inputFolder;
    public final BooleanProperty includeSubfolders;

    // ---------------------------------------------------------------- atualizações
    public final StringProperty updateSummary = new SimpleStringProperty(
            "Nenhuma atualização é aplicada automaticamente. A verificação depende do canal oficial configurado.");
    public final IntegerProperty updateProgress = new SimpleIntegerProperty(0);
    public final BooleanProperty updating = new SimpleBooleanProperty(false);
    public final BooleanProperty canDownloadUpdate = new SimpleBooleanProperty(false);
    public final BooleanProperty canApplyUpdate = new SimpleBooleanProperty(false);

    // ---------------------------------------------------------------- retenção
    public final ObservableList<RetentionOption> retentionOptions = FXCollections.observableArrayList(RETENTION_OPTIONS);
    public final ObjectProperty<RetentionOption> retention = new SimpleObjectProperty<>(RETENTION_OPTIONS.getFirst());
    public final StringProperty retentionSummary = new SimpleStringProperty(
            "Nenhum documento é excluído automaticamente. Escolha um prazo apenas para localizar itens que merecem revisão.");

    private boolean restoring;

    public SettingsModel(EmailAccountConnectionService email, ClientCatalogService catalog, ProtectedBackupService backup,
            AppUpdates.Service updates, WorkspacePreferences.Store preferences, DocumentsModel documents, ReportsModel reports,
            DispatchModel dispatch, ShellState shell, UiTasks tasks, Clock clock) {
        this.email = email;
        this.catalog = catalog;
        this.backup = backup;
        this.updates = updates;
        this.preferences = preferences;
        this.documents = Objects.requireNonNull(documents);
        this.reports = Objects.requireNonNull(reports);
        this.dispatch = dispatch;
        this.shell = Objects.requireNonNull(shell);
        this.tasks = Objects.requireNonNull(tasks);
        this.clock = Objects.requireNonNull(clock);
        this.reportOutputDirectory = reports.outputDirectory;
        this.archiveDirectory = documents.archiveDirectory;
        this.inputFolder = documents.inputFolder;
        this.includeSubfolders = documents.includeSubfolders;
        restorePreferences();
        // Qualquer mudança de preferência é salva (equivalente a QueuePreferencesSave).
        reportOutputDirectory.addListener((o, a, b) -> savePreferences());
        archiveDirectory.addListener((o, a, b) -> savePreferences());
        inputFolder.addListener((o, a, b) -> savePreferences());
        includeSubfolders.addListener((o, a, b) -> savePreferences());
        keepEmailSession.addListener((o, a, b) -> savePreferences());
        retention.addListener((o, a, b) -> savePreferences());
        shell.selectedYear().addListener((o, a, b) -> savePreferences());
        shell.selectedMonth().addListener((o, a, b) -> savePreferences());
    }

    public void onCatalogChanged(Runnable action) {
        catalogChanged = Objects.requireNonNull(action);
    }

    // ================================================================ preferências

    private void restorePreferences() {
        if (preferences == null) {
            return;
        }
        WorkspacePreferences loaded;
        try {
            loaded = preferences.load().orElse(null);
        } catch (RuntimeException e) {
            loaded = null;
        }
        final var prefs = loaded;
        if (prefs == null) {
            return;
        }
        restoring = true;
        try {
            if (notBlank(prefs.inputFolderPath()) && inputFolder.get().isBlank()) {
                inputFolder.set(prefs.inputFolderPath());
            }
            if (notBlank(prefs.documentArchiveDirectory())) {
                archiveDirectory.set(Path.of(prefs.documentArchiveDirectory()));
            }
            if (notBlank(prefs.reportOutputDirectory())) {
                reportOutputDirectory.set(prefs.reportOutputDirectory());
            }
            includeSubfolders.set(prefs.includeSubfolders());
            keepEmailSession.set(prefs.keepEmailSession());
            retention.set(RETENTION_OPTIONS.stream().filter(r -> r.months() == prefs.retentionReviewMonths()).findFirst()
                    .orElse(RETENTION_OPTIONS.getFirst()));
            if (prefs.selectedYear() != null) {
                shell.years().stream().filter(y -> prefs.selectedYear().equals(y.year())).findFirst()
                        .ifPresent(shell.selectedYear()::set);
            }
            if (prefs.selectedMonth() != null) {
                shell.months().stream().filter(m -> prefs.selectedMonth().equals(m.month())).findFirst()
                        .ifPresent(shell.selectedMonth()::set);
            }
        } finally {
            restoring = false;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void savePreferences() {
        if (restoring || preferences == null) {
            return;
        }
        var year = shell.selectedYear().get() == null ? null : shell.selectedYear().get().year();
        var month = shell.selectedMonth().get() == null ? null : shell.selectedMonth().get().month();
        var input = emptyToNull(inputFolder.get());
        var archive = archiveDirectory.get() == null ? null : archiveDirectory.get().toString();
        var reportsDir = emptyToNull(reportOutputDirectory.get());
        var subfolders = includeSubfolders.get();
        var keep = keepEmailSession.get();
        var months = retention.get() == null ? 0 : retention.get().months();
        tasks.run(() -> {
            var current = preferences.load().orElse(null);
            var visibility = current == null ? null : current.historyVisibility();
            var channel = current == null ? "stable" : current.updateChannel();
            preferences.save(new WorkspacePreferences(input, archive, reportsDir, subfolders, year,
                    month == null || month == 0 ? null : month, keep, months, channel, visibility));
        }, () -> { }, error -> shell.status("As preferências não puderam ser salvas: " + ErrorMessages.friendly(error)));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ================================================================ e-mail

    public BooleanBinding isGmailProvider() {
        return Bindings.createBooleanBinding(() -> DispatchWorkflowOptions.GMAIL_PROVIDER.equals(providerKey.get()), providerKey);
    }

    public BooleanBinding isMicrosoftProvider() {
        return Bindings.createBooleanBinding(() -> DispatchWorkflowOptions.GRAPH_PROVIDER.equals(providerKey.get()), providerKey);
    }

    /** Conectar só existe com provedor externo configurado e ainda desconectado. */
    private BooleanBinding canConnect() {
        return Bindings.createBooleanBinding(() -> emailConfigured.get() && !emailConnected.get()
                && !DispatchWorkflowOptions.FAKE_PROVIDER.equals(providerKey.get()), emailConfigured, emailConnected, providerKey);
    }

    public BooleanBinding canConnectGmail() {
        return isGmailProvider().and(canConnect());
    }

    public BooleanBinding canConnectMicrosoft() {
        return isMicrosoftProvider().and(canConnect());
    }

    public BooleanBinding canDisconnect() {
        return Bindings.createBooleanBinding(() -> emailConnected.get()
                && !DispatchWorkflowOptions.FAKE_PROVIDER.equals(providerKey.get()), emailConnected, providerKey);
    }

    public void refreshEmail() {
        if (email == null) {
            return;
        }
        tasks.run(email::status, this::applyEmailStatus, error -> shell.status(ErrorMessages.friendly(error)));
    }

    private void applyEmailStatus(EmailAccountSession.Status status) {
        var key = status.providerKey();
        var name = switch (key == null ? "" : key) {
            case DispatchWorkflowOptions.GMAIL_PROVIDER -> "Gmail";
            case DispatchWorkflowOptions.GRAPH_PROVIDER -> "Microsoft 365";
            default -> "local";
        };
        providerKey.set(key);
        emailConfigured.set(status.configured());
        emailConnected.set(status.connected());
        var fake = DispatchWorkflowOptions.FAKE_PROVIDER.equals(key);
        emailStatus.set(status.connected() ? fake ? "Simulação local ativa; nenhuma mensagem sai desta máquina."
                : "Conta " + name + " conectada como " + status.displayName() + "."
                : status.configured() ? "Conta " + name + " configurada, mas desconectada."
                : "Integração " + name + " ainda não configurada nesta instalação.");
        emailHelp.set(fake
                ? "Use Teste seguro para conferir todo o fluxo sem acessar uma caixa postal. Gmail e Outlook só ficam disponíveis após o registro oficial do aplicativo no provedor escolhido."
                : "A conexão abre a página segura do " + name + " no navegador. O aplicativo nunca solicita nem armazena sua senha.");
        technicalDetails.set(switch (key == null ? "" : key) {
            case DispatchWorkflowOptions.GMAIL_PROVIDER -> status.configured()
                    ? "Conta Google configurada para conexão segura pelo aplicativo." : fakeDetails();
            case DispatchWorkflowOptions.GRAPH_PROVIDER -> status.configured()
                    ? "Microsoft Graph configurado com autorização delegada." : fakeDetails();
            default -> fakeDetails();
        });
        shell.showEmailSetupNotice().set(fake || !status.connected());
    }

    private static String fakeDetails() {
        return "Modo seguro local ativo; nenhuma integração externa está habilitada.";
    }

    public void connectEmail() {
        if (email == null) {
            shell.status("A conexão de e-mail ainda não está disponível nesta instalação.");
            return;
        }
        tasks.run(email::connect, status -> {
            applyEmailStatus(status);
            shell.status(status.connected()
                    ? "Conta de e-mail conectada. Os envios continuam sujeitos à conferência e à confirmação final."
                    : "A conta de e-mail não foi conectada.");
            if (dispatch != null) {
                dispatch.reload();
            }
        }, this::emailFailed);
    }

    public void disconnectEmail() {
        if (email == null) {
            return;
        }
        tasks.run(email::disconnect, () -> {
            refreshEmail();
            shell.status("Conta de e-mail desconectada com segurança.");
            technicalDetails.set("A sessão local de autorização foi removida do cofre protegido do sistema.");
            if (dispatch != null) {
                dispatch.reload();
            }
        }, this::emailFailed);
    }

    /** Ao fechar: sem “manter conectada”, desconecta (equivalente ao encerramento do .NET). */
    public void onApplicationClosing() {
        if (!keepEmailSession.get() && email != null && emailConnected.get()
                && !DispatchWorkflowOptions.FAKE_PROVIDER.equals(providerKey.get())) {
            try {
                email.disconnect();
            } catch (RuntimeException ignored) {
                // Encerramento não deve falhar por causa da sessão.
            }
        }
    }

    private void emailFailed(Throwable error) {
        shell.status(error instanceof EmailAccountSession.EmailAccountSessionException e
                ? "A conta de e-mail precisa de atenção: " + e.getMessage() : ErrorMessages.friendly(error));
    }

    // ================================================================ cópia de segurança

    private char[] passwordChars() {
        var text = backupPassword.get() == null ? "" : backupPassword.get();
        return text.toCharArray();
    }

    /** Gera a cópia protegida e grava de forma atômica em {@code target}. */
    public void saveBackup(Path target) {
        if (target == null) {
            return;
        }
        if (catalog == null || backup == null) {
            shell.status("A cópia de segurança não está disponível nesta instalação.");
            return;
        }
        var password = passwordChars();
        tasks.run(() -> {
            var json = Json.writeBytes(catalog.export());
            try {
                var protectedContent = backup.protect(json, password);
                writeAtomically(target, protectedContent);
                return target;
            } finally {
                Arrays.fill(json, (byte) 0);
                Arrays.fill(password, '\0');
            }
        }, saved -> {
            backupPassword.set("");
            shell.status("Cópia protegida salva em " + saved + ". Guarde a senha separadamente.");
        }, this::backupFailed);
    }

    private static void writeAtomically(Path target, byte[] content) {
        try {
            var directory = target.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            var temp = Files.createTempFile(directory, ".backup-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Abre a cópia, confere (simulação) e mostra o resumo antes da confirmação. */
    public void previewRestore(Path source) {
        if (source == null) {
            return;
        }
        canApplyRestore.set(false);
        pendingRestore = null;
        restoreSummary.set("Conferindo a cópia de segurança…");
        if (catalog == null || backup == null) {
            shell.status("A restauração não está disponível nesta instalação.");
            return;
        }
        var password = passwordChars();
        tasks.run(() -> {
            byte[] content = null;
            try {
                content = backup.unprotect(Files.readAllBytes(source), password);
                var document = Json.read(content, ClientCatalogTransferDocument.class);
                if (document == null) {
                    throw new IllegalStateException("A cópia de segurança está vazia ou inválida.");
                }
                var result = catalog.importCatalog(new ClientCatalogImportRequest(true, true, document));
                return new Object[] {document, result};
            } finally {
                if (content != null) {
                    Arrays.fill(content, (byte) 0);
                }
                Arrays.fill(password, '\0');
            }
        }, pair -> {
            pendingRestore = (ClientCatalogTransferDocument) pair[0];
            var result = (ClientCatalogImportResult) pair[1];
            canApplyRestore.set(true);
            restoreSummary.set("Conferência concluída: " + result.clientsCreated() + " cliente(s) novo(s) e "
                    + result.clientsUpdated() + " cadastro(s) a atualizar.");
            shell.status("Cópia conferida. Revise o resumo e confirme somente se estiver correto.");
        }, error -> {
            restoreSummary.set(error instanceof ProtectedBackupService.ProtectedBackupException e ? e.getMessage()
                    : "A cópia escolhida não pôde ser conferida. Selecione outro arquivo válido.");
            backupFailed(error);
        });
    }

    public void confirmRestore() {
        if (!canApplyRestore.get() || pendingRestore == null) {
            shell.status("Escolha e confira uma cópia de segurança antes de restaurar.");
            return;
        }
        var document = pendingRestore;
        tasks.run(() -> catalog.importCatalog(new ClientCatalogImportRequest(false, true, document)), result -> {
            canApplyRestore.set(false);
            pendingRestore = null;
            backupPassword.set("");
            restoreSummary.set("Restauração concluída. Gere uma nova cópia de segurança após alterações importantes.");
            shell.status("Restauração concluída: " + (result.clientsCreated() + result.clientsUpdated()) + " cliente(s).");
            catalogChanged.run();
        }, this::backupFailed);
    }

    private void backupFailed(Throwable error) {
        shell.status(switch (error) {
            case ProtectedBackupService.ProtectedBackupException e -> e.getMessage();
            case UncheckedIOException e -> "Não foi possível ler ou gravar o arquivo escolhido. Confira a pasta e as permissões.";
            default -> ErrorMessages.friendly(error);
        });
    }

    // ================================================================ pastas

    public StringBinding inputFolderDisplay() {
        return documents.inputFolderDisplay();
    }

    public StringBinding archiveDisplay() {
        return Bindings.createStringBinding(() -> archiveDirectory.get() == null ? "" : archiveDirectory.get().toString(),
                archiveDirectory);
    }

    public void chooseInputFolder(Path folder) {
        if (folder != null) {
            inputFolder.set(folder.toString());
            shell.status("Pasta de entrada definida: " + folder + ".");
        }
    }

    public void chooseArchiveFolder(Path folder) {
        if (folder != null) {
            archiveDirectory.set(folder);
            shell.status("Novos PDFs serão organizados em " + folder + " por ano e mês.");
        }
    }

    public void chooseReportFolder(Path folder) {
        reports.chooseOutputDirectory(folder);
    }

    // ================================================================ atualizações

    public String currentVersion() {
        return AppVersion.CURRENT.toString();
    }

    public void checkForUpdates() {
        if (updates == null) {
            updateSummary.set("O atualizador não está disponível nesta instalação.");
            return;
        }
        updating.set(true);
        updateProgress.set(0);
        canDownloadUpdate.set(false);
        canApplyUpdate.set(false);
        updateSummary.set("Consultando o canal oficial sem enviar dados de clientes…");
        tasks.run(() -> updates.check(AppUpdates.Channel.STABLE), this::applyUpdateSnapshot, this::updateFailed);
    }

    public void downloadUpdate() {
        if (updates == null || !canDownloadUpdate.get()) {
            updateSummary.set("Verifique novamente antes de baixar uma atualização.");
            return;
        }
        updating.set(true);
        canDownloadUpdate.set(false);
        canApplyUpdate.set(false);
        updateSummary.set("Baixando e conferindo o pacote…");
        tasks.run(() -> updates.download(value -> tasks.ui(() -> updateProgress.set(value))), this::applyUpdateSnapshot,
                error -> {
                    updateProgress.set(0);
                    updateFailed(error);
                });
    }

    public void applyUpdate() {
        if (updates == null || !canApplyUpdate.get()) {
            updateSummary.set("Baixe e confira a atualização antes de reiniciar.");
            return;
        }
        try {
            updateSummary.set("Fechando para aplicar a atualização conferida…");
            updates.applyAndRestart();
        } catch (RuntimeException e) {
            updateSummary.set(e.getMessage() == null ? "Não foi possível aplicar a atualização." : e.getMessage());
        }
    }

    private void applyUpdateSnapshot(AppUpdates.Snapshot snapshot) {
        updating.set(false);
        updateProgress.set(snapshot.progress());
        updateSummary.set(snapshot.message());
        canDownloadUpdate.set(snapshot.canDownload());
        canApplyUpdate.set(snapshot.canRestart());
        shell.status(switch (snapshot.state()) {
            case AVAILABLE -> "Versão " + snapshot.latestVersion() + " disponível no canal Estável.";
            case READY_TO_RESTART -> "Versão " + snapshot.latestVersion() + " pronta para ser aplicada ao reiniciar.";
            case UP_TO_DATE -> "O aplicativo está atualizado.";
            case FAILED -> "Não foi possível verificar atualizações agora.";
            default -> snapshot.message();
        });
    }

    private void updateFailed(Throwable error) {
        updating.set(false);
        updateSummary.set(error.getMessage() == null ? ErrorMessages.friendly(error) : error.getMessage());
    }

    // ================================================================ retenção

    /** Localiza PDFs antigos no acervo; nunca apaga nada ({@code ReviewRetention}). */
    public void reviewRetention() {
        var months = retention.get() == null ? 0 : retention.get().months();
        if (months == 0) {
            retentionSummary.set("Retenção por tempo indeterminado: nenhum arquivo foi selecionado para revisão e nada foi excluído.");
            return;
        }
        var directory = archiveDirectory.get();
        if (directory == null || !Files.isDirectory(directory)) {
            retentionSummary.set("A pasta do acervo ainda não existe. Nenhum arquivo foi alterado.");
            return;
        }
        var cutoff = clock.nowUtc().atZoneSameInstant(Clock.BRAZIL).minusMonths(months);
        tasks.run(() -> {
            try (Stream<Path> files = Files.walk(directory)) {
                long count = 0;
                long bytes = 0;
                for (var path : (Iterable<Path>) files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))::iterator) {
                    var modified = Files.getLastModifiedTime(path).toInstant();
                    if (modified.isBefore(cutoff.toInstant())) {
                        count++;
                        bytes += Files.size(path);
                    }
                }
                return new long[] {count, bytes};
            }
        }, result -> retentionSummary.set("Revisão encontrou " + result[0] + " PDF(s), somando " + formatBytes(result[1])
                + ", anteriores a " + cutoff.format(DateTimeFormatter.ofPattern("MM/yyyy"))
                + ". Nada foi excluído; confirme obrigações fiscais e a política do escritório antes de qualquer ação fora do aplicativo."),
                error -> retentionSummary.set("Não foi possível ler todo o acervo. Confira as permissões; nenhum arquivo foi alterado."));
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return "%.1f GB".formatted(bytes / 1_073_741_824d);
        }
        if (bytes >= 1_048_576L) {
            return "%.1f MB".formatted(bytes / 1_048_576d);
        }
        if (bytes >= 1_024L) {
            return "%.1f KB".formatted(bytes / 1_024d);
        }
        return bytes + " bytes";
    }

    // ================================================================ suporte

    public ObservableList<br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario> scenarios() {
        return dispatch.scenarios;
    }

    public ObjectProperty<br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario> scenario() {
        return dispatch.scenario;
    }

    public StringProperty testDestination() {
        return dispatch.testDestination;
    }

    /** Ao abrir a seção. */
    public void open() {
        refreshEmail();
    }
}
