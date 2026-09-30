package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.desktop.DesktopServices;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.io.IOException;
import java.nio.file.Files;
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

/** Relatórios de ponta a ponta: recorte, situação das comunicações e arquivos gerados (XLSX, PDF, CSV). */
class ReportsModelTest {

    @TempDir
    Path temp;
    private DesktopServices services;
    private ShellState shell;
    private DocumentsModel documents;
    private ClientsModel clients;
    private DispatchModel dispatch;
    private ReportsModel reports;
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
    void exportsMonthReportWithCommunicationRowsAndOrganizedFolders() throws IOException {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        importAndApprove(payroll("folha.pdf", "Padaria Sao Joao Ltda", "11.222.333/0001-81"));
        dispatch.refresh();
        documents.selectedGroup.set(dispatch.approvedGroups.getFirst());
        dispatch.prepareSelected();
        dispatch.approveSelected();
        dispatch.executeSelected();

        reports.open();
        assertThat(statuses.getLast()).isEqualTo("Relatórios prontos para o recorte MARÇO 2026.");
        assertThat(reports.scopeSummary().get()).isEqualTo("Recorte escolhido: Todos os clientes • MARÇO 2026");
        assertThat(reports.rows).singleElement().satisfies(r -> {
            assertThat(r.clientName()).isEqualTo("Padaria Sao Joao Ltda");
            assertThat(r.messageReference()).matches("Mensagem #[0-9A-F]{6}");
            assertThat(r.groupSummary()).endsWith(" • 1 documento(s)");
            assertThat(r.documentSummary()).isEqualTo("Folha de pagamento • folha.pdf");
            assertThat(r.operationResult()).isEqualTo("Simulação local concluída");
        });
        assertThat(reports.clients).extracting(ReportsModel.ReportClient::displayName).contains("Padaria Sao Joao Ltda");

        reports.export();
        var expectedDir = temp.resolve("relatorios").resolve("2026").resolve("03");
        assertThat(statuses.getLast()).startsWith("Planilha, PDF e 5 arquivos auxiliares de “Todos os clientes • MARÇO 2026” foram salvos em "
                + expectedDir);
        assertThat(Path.of(reports.lastReportPath.get())).exists().startsWith(expectedDir).hasExtension("xlsx");
        assertThat(Path.of(reports.lastReportPdfPath.get())).exists().hasExtension("pdf");
        assertThat(Files.size(Path.of(reports.lastReportPdfPath.get()))).isPositive();

        // Outro mês: sem linhas; cliente específico organiza a pasta por cliente.
        reports.month.set(reports.monthOptions.stream().filter(m -> m.month() == 4).findFirst().orElseThrow());
        assertThat(reports.rows).isEmpty();
        reports.scope.set(ReportsModel.Scope.ALL_PERIODS);
        reports.clientFilter.set(ReportsModel.ClientFilter.SELECTED_CLIENT);
        assertThat(reports.canExport().get()).isFalse();
        reports.export();
        assertThat(statuses.getLast()).isEqualTo("Escolha o cliente do relatório.");
        reports.client.set(reports.clients.getFirst());
        assertThat(reports.rows).hasSize(1);
        reports.export();
        assertThat(reports.lastReportPath.get()).contains("por-cliente").contains("todos-os-periodos");
    }

    @Test
    void rangeMustNotBeInverted() {
        reports.scope.set(ReportsModel.Scope.RANGE);
        assertThat(reports.canExport().get()).isTrue();
        assertThat(reports.scopeSummary().get()).isEqualTo("Recorte escolhido: Todos os clientes • MARÇO 2026 a MARÇO 2026");
        reports.startMonth.set(reports.monthOptions.stream().filter(m -> m.month() == 5).findFirst().orElseThrow());
        assertThat(reports.canExport().get()).isFalse();
        reports.export();
        assertThat(statuses.getLast()).isEqualTo("Escolha um intervalo válido, do mês inicial ao mês final.");
        assertThat(ReportsModel.storageSegment(reports.buildFilter())).isEqualTo(Path.of("intervalos", "2026-05_a_2026-03"));
        assertThat(ReportsModel.yearMonth("03/2026")).containsExactly(2026, 3);
        assertThat(ReportsModel.yearMonth("2026")).isNull();
    }
}
