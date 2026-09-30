package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.desktop.DesktopServices;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configurações: cópia protegida (salvar, conferir, restaurar), preferências e retenção. */
class SettingsModelTest {

    @TempDir
    Path temp;
    private DesktopServices services;
    private ShellState shell;
    private DocumentsModel documents;
    private ClientsModel clients;
    private DispatchModel dispatch;
    private ReportsModel reports;
    private SettingsModel settings;
    private final List<String> statuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        services = DesktopServices.create(temp.resolve("data"), true);
        shell = new ShellState(2026, 3);
        shell.statusMessage().addListener((obs, old, now) -> statuses.add(now));
        documents = new DocumentsModel(services.review(), services.recognition(), shell, UiTasks.synchronous(),
                services.documentArchiveDirectory());
        clients = new ClientsModel(services.catalog(), UiTasks.synchronous(), shell::status, documents::reload);
        dispatch = new DispatchModel(services.dispatch(), services.emailConnection(), documents, shell, UiTasks.synchronous());
        reports = new ReportsModel(services.dispatch(), services.catalog(), dispatch, documents, shell, UiTasks.synchronous(),
                temp.resolve("relatorios"), 2026, 3);
        settings = newSettings();
    }

    private SettingsModel newSettings() {
        return new SettingsModel(services.emailConnection(), services.catalog(), services.backup(), services.updates(),
                services.preferences(), documents, reports, dispatch, shell, UiTasks.synchronous(), services.clock());
    }

    @AfterEach
    void tearDown() {
        services.close();
    }

    private void registerClient(String name, String cnpj, boolean withTemplate) {
        clients.newClient();
        clients.legalName.set(name);
        clients.primaryTaxId.set(cnpj);
        clients.newRecipientName.set("Financeiro");
        clients.newRecipientEmail.set("financeiro@" + name.toLowerCase().replaceAll("[^a-z]", "") + ".com.br");
        clients.addRecipient();
        clients.saveClient();
        assertThat(clients.feedbackIsError.get()).as(clients.feedbackMessage.get()).isFalse();
        if (withTemplate) {
            clients.applyStandardTemplate(PersonTypeModel.LEGAL_ENTITY);
            clients.saveTemplate();
            assertThat(clients.feedbackIsError.get()).as(clients.feedbackMessage.get()).isFalse();
        }
    }

    private Path payroll(String name, String employer, String cnpj) throws IOException {
        var file = temp.resolve(name);
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 740);
                for (var line : List.of("FOLHA DE PAGAMENTO", "Empregador: " + employer, "Empregador CNPJ: " + cnpj,
                        "Competencia: 03/2026", "Total da folha: R$ 12.345,67", "Arquivo " + name)) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private void importAndApprove(Path... files) {
        documents.loadIfNeeded();
        documents.importFiles(List.of(files));
        documents.approveAllEligible();
        assertThat(documents.groups).allSatisfy(g -> assertThat(g.isApproved()).isTrue());
    }

    @Test
    void protectedBackupRoundTripNeedsPasswordAndConfirmation() {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        var file = temp.resolve("copia.fmbackup");

        settings.backupPassword.set("curta");
        settings.saveBackup(file);
        assertThat(file).doesNotExist();

        settings.backupPassword.set("senha-muito-segura-2026");
        settings.saveBackup(file);
        assertThat(file).exists();
        assertThat(statuses.getLast()).startsWith("Cópia protegida salva em ");
        assertThat(settings.backupPassword.get()).isEmpty();
        assertThat(new String(Files.readAllBytesUnchecked(file))).doesNotContain("Padaria");

        settings.backupPassword.set("senha-errada-qualquer");
        settings.previewRestore(file);
        assertThat(settings.canApplyRestore.get()).isFalse();

        settings.backupPassword.set("senha-muito-segura-2026");
        settings.previewRestore(file);
        assertThat(settings.canApplyRestore.get()).isTrue();
        assertThat(settings.restoreSummary.get()).isEqualTo("Conferência concluída: 0 cliente(s) novo(s) e 1 cadastro(s) a atualizar.");
        settings.confirmRestore();
        assertThat(statuses.getLast()).isEqualTo("Restauração concluída: 1 cliente(s).");
        assertThat(settings.canApplyRestore.get()).isFalse();
    }

    @Test
    void preferencesPersistAndRetentionNeverDeletes() throws Exception {
        settings.chooseArchiveFolder(temp.resolve("acervo"));
        settings.includeSubfolders.set(false);
        settings.retention.set(SettingsModel.RETENTION_OPTIONS.get(3));
        var reopened = newSettings();
        assertThat(reopened.archiveDirectory.get()).isEqualTo(temp.resolve("acervo"));
        assertThat(reopened.includeSubfolders.get()).isFalse();
        assertThat(reopened.retention.get().months()).isEqualTo(6);

        reopened.reviewRetention();
        assertThat(reopened.retentionSummary.get()).isEqualTo("A pasta do acervo ainda não existe. Nenhum arquivo foi alterado.");
        var old = temp.resolve("acervo").resolve("2020").resolve("01").resolve("antigo.pdf");
        Files.createDirectories(old.getParent());
        Files.write(old, new byte[2048]);
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(0));
        reopened.reviewRetention();
        assertThat(reopened.retentionSummary.get()).startsWith("Revisão encontrou 1 PDF(s), somando 2.0 KB").contains("Nada foi excluído");
        assertThat(old).exists();

        reopened.open();
        assertThat(reopened.emailStatus.get()).isEqualTo("Simulação local ativa; nenhuma mensagem sai desta máquina.");
        assertThat(reopened.canDisconnect().get()).isFalse();
        reopened.checkForUpdates();
        assertThat(reopened.canDownloadUpdate.get()).isFalse();
        assertThat(reopened.updateSummary.get()).isNotBlank();
    }

    private static final class Files {
        static byte[] readAllBytesUnchecked(Path p) {
            try {
                return java.nio.file.Files.readAllBytes(p);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        static void createDirectories(Path p) throws IOException {
            java.nio.file.Files.createDirectories(p);
        }

        static void write(Path p, byte[] b) throws IOException {
            java.nio.file.Files.write(p, b);
        }

        static void setLastModifiedTime(Path p, java.nio.file.attribute.FileTime t) throws IOException {
            java.nio.file.Files.setLastModifiedTime(p, t);
        }
    }
}
