package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.desktop.DesktopServices;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
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

/** Histórico: linha do tempo, filtros, limpeza apenas visual (persistida) e ocorrências. */
class HistoryModelTest {

    @TempDir
    Path temp;
    private DesktopServices services;
    private ShellState shell;
    private DocumentsModel documents;
    private ClientsModel clients;
    private DispatchModel dispatch;
    private HistoryModel history;
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
        history = newHistory();
    }

    private HistoryModel newHistory() {
        return new HistoryModel(documents, dispatch, services.catalog(), services.incidents(), services.preferences(), shell,
                UiTasks.synchronous(), services.clock(), java.time.ZoneId.of("America/Sao_Paulo"));
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

    private void completeSafeTest(FakeDeliveryScenario scenario) throws IOException {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        importAndApprove(payroll("folha.pdf", "Padaria Sao Joao Ltda", "11.222.333/0001-81"));
        dispatch.refresh();
        dispatch.scenario.set(scenario);
        documents.selectedGroup.set(dispatch.approvedGroups.getFirst());
        dispatch.prepareSelected();
        dispatch.approveSelected();
        dispatch.executeSelected();
    }

    @Test
    void timelineShowsFriendlyActionsAndFiltersBySearchAndClient() throws IOException {
        completeSafeTest(FakeDeliveryScenario.SUCCESS);
        history.open();

        assertThat(history.reviewRows).extracting(HistoryModel.TimelineRow::action)
                .contains("Documento importado", "Conjunto liberado para mensagem");
        assertThat(history.reviewRows).allSatisfy(r -> assertThat(r.timestamp()).matches("\\d{2}/\\d{2}/\\d{4} · \\d{2}:\\d{2}"));
        assertThat(history.dispatchRows).extracting(HistoryModel.TimelineRow::action)
                .contains("Mensagem preparada", "Mensagem aprovada", "Operação de e-mail iniciada",
                        "Operação no serviço de e-mail concluída");
        assertThat(history.dispatchRows).allSatisfy(r -> assertThat(r.clientName()).isEqualTo("Padaria Sao Joao Ltda"));
        assertThat(history.dispatchRows.getFirst().context()).startsWith("03/2026").contains("1 anexo(s): Folha de pagamento");
        assertThat(history.reviewHeader().get()).startsWith("Documentos conferidos e aprovados · ");

        history.searchText.set("folha.pdf");
        history.applyFilters();
        assertThat(history.reviewRows).isNotEmpty();
        assertThat(history.appliedFilterSummary.get()).isEqualTo(
                "Exibindo todos os dias · todos os clientes · todos os documentos · competência MARÇO 2026 · busca “folha.pdf”.");
        history.searchText.set("liberado");
        history.applyFilters();
        assertThat(history.reviewRows).isNotEmpty().allSatisfy(r -> assertThat(r.action()).containsIgnoringCase("liberad"));
        history.searchText.set("inexistente-xyz");
        history.applyFilters();
        assertThat(history.reviewRows).isEmpty();
        assertThat(history.dispatchRows).isEmpty();

        // Cliente específico: libera as alterações cadastrais em seção própria.
        history.clearFilters();
        history.clientFilter.set(history.clientFilters.stream().filter(c -> c.clientId() != null).findFirst().orElseThrow());
        history.applyFilters();
        assertThat(history.catalogHeader().get()).isEqualTo("Alterações do cadastro · Padaria Sao Joao Ltda");
        assertThat(history.catalogRows).isNotEmpty().extracting(HistoryModel.TimelineRow::action).contains("Cadastro criado");

        // Intervalo inválido mostra o erro sem aplicar.
        history.timeScope.set(HistoryModel.TIME_SCOPES.getLast());
        history.filterEndDate.set(history.filterStartDate.get().minusDays(1));
        history.applyFilters();
        assertThat(history.filterError.get()).isEqualTo("O fim do intervalo deve ser posterior ao início.");
        assertThat(history.hasFilterError().get()).isTrue();
    }

    @Test
    void cleanupOnlyHidesAndIsRestoredAcrossSessions() throws IOException {
        completeSafeTest(FakeDeliveryScenario.SUCCESS);
        history.open();
        var total = history.reviewRows.size() + history.dispatchRows.size();
        assertThat(total).isPositive();

        history.archiveView();
        assertThat(statuses.getLast()).startsWith("Histórico limpo da visualização.");
        assertThat(history.reviewRows).isEmpty();
        assertThat(history.dispatchRows).isEmpty();
        assertThat(history.hasHidden.get()).isTrue();
        assertThat(history.hiddenCountText.get()).isEqualTo("Ocultos nesta visualização: " + total + " registro(s).");
        history.showHidden.set(true);
        assertThat(history.reviewRows.size() + history.dispatchRows.size()).isEqualTo(total);
        history.showHidden.set(false);

        // A regra é preferência persistida: nova sessão continua ocultando.
        var reopened = newHistory();
        reopened.open();
        assertThat(reopened.reviewRows).isEmpty();
        assertThat(reopened.hasHidden.get()).isTrue();

        reopened.restoreView();
        assertThat(statuses.getLast()).isEqualTo("Todo o histórico preservado voltou a aparecer na tela.");
        assertThat(reopened.reviewRows.size() + reopened.dispatchRows.size()).isEqualTo(total);
        assertThat(newHistory().hasHidden.get()).isFalse();
    }

    @Test
    void incidentFromAmbiguousAttemptIsRedactedAndFollowsTransitions() throws IOException {
        completeSafeTest(FakeDeliveryScenario.AMBIGUOUS);
        history.open();
        history.startIncidentFor(dispatch.attempts.getFirst());
        assertThat(history.incidentsExpanded.get()).isTrue();
        assertThat(HistoryModel.attemptLabel(history.incidentAttempt.get(), history.zone())).startsWith("Tentativa em ");

        history.incidentCategory.set(br.com.contadoresassociados.folhas.application.incidents.IncidentManagement.Category.AMBIGUOUS_PROVIDER_RESULT);
        history.incidentSummary.set("Curto");
        history.openIncident();
        assertThat(statuses.getLast()).startsWith("Não foi possível concluir esta etapa: Informe entre 20");

        history.incidentSummary.set("Resultado incerto ao enviar para financeiro@padaria.com.br; conferir antes de repetir.");
        history.openIncident();
        assertThat(statuses.getLast()).startsWith("Ocorrência registrada com histórico protegido.");
        assertThat(history.incidents).singleElement().satisfies(i -> {
            assertThat(i.summary()).doesNotContain("financeiro@padaria.com.br");
            assertThat(FriendlyText.of(i.status())).isEqualTo("Aberto");
        });
        assertThat(history.incidentSummary.get()).isEmpty();

        history.incidentStatus.set(HistoryModel.INCIDENT_STATUSES.get(1));
        history.incidentNote.set("Apurando com o provedor");
        history.transitionIncident();
        assertThat(statuses.getLast()).isEqualTo("Situação da ocorrência atualizada; o evento anterior foi preservado.");
        assertThat(FriendlyText.of(history.incidents.getFirst().status())).isEqualTo("Em apuração");
    }
}
