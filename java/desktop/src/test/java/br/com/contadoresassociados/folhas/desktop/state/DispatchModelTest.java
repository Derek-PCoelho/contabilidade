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

/**
 * Mensagens e envios de ponta a ponta com os serviços reais do Desktop: PDF → revisão →
 * liberação → preparar → conferir → aprovar → concluir no modo local seguro.
 */
class DispatchModelTest {

    @TempDir
    Path temp;
    private DesktopServices services;
    private ShellState shell;
    private DocumentsModel documents;
    private ClientsModel clients;
    private DispatchModel dispatch;
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
    void safeTestFlowPreparesApprovesAndCompletesWithoutSendingEmail() throws IOException {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        importAndApprove(payroll("folha.pdf", "Padaria Sao Joao Ltda", "11.222.333/0001-81"));
        dispatch.refresh();

        assertThat(dispatch.approvedGroups).hasSize(1);
        assertThat(dispatch.isPrepareStepCurrent().get()).isTrue();
        assertThat(dispatch.safetyTitle().get()).isEqualTo("Simulação local — nenhum e-mail será enviado");
        assertThat(dispatch.connectionStatus.get()).isEqualTo("Simulação local ativa; nenhuma mensagem sai desta máquina.");
        assertThat(dispatch.operationModes).containsExactly(DispatchOperationMode.TEST, DispatchOperationMode.DRAFT);
        assertThat(dispatch.queueEmptyMessage().get()).startsWith("Ainda não há mensagens preparadas");

        documents.selectedGroup.set(dispatch.approvedGroups.getFirst());
        assertThat(dispatch.canPrepareSelected().get()).isTrue();
        dispatch.prepareSelected();

        var item = dispatch.selectedItem.get();
        assertThat(item).isNotNull();
        assertThat(item.state()).as(item.blocks().toString()).isEqualTo(DispatchItemState.READY_FOR_APPROVAL);
        assertThat(statuses.getLast()).isEqualTo(dispatch.selectedReference().get()
                + " preparada. Confira destinatários, texto e anexos antes de aprovar.");
        assertThat(dispatch.selectedReference().get()).matches("Mensagem #[0-9A-F]{6}");
        assertThat(dispatch.selectedSubject().get()).startsWith("[TESTE — NÃO ENVIAR AO CLIENTE] ");
        assertThat(dispatch.selectedRecipients().get()).isEqualTo("auditoria@example.invalid");
        assertThat(dispatch.selectedAttachments).hasSize(1);
        assertThat(dispatch.selectedContextSummary().get()).startsWith("Padaria Sao Joao Ltda • ").endsWith(" • 1 anexo(s)");
        assertThat(dispatch.isApprovalStepCurrent().get()).isTrue();
        assertThat(dispatch.canSelectOperationMode().get()).isFalse();
        assertThat(dispatch.modeHelp().get()).startsWith("Esta mensagem foi preparada como Teste seguro.");
        assertThat(dispatch.visibleItems).hasSize(1);
        assertThat(dispatch.queueSummary().get()).isEqualTo("1 a fazer • 0 concluída(s) • 0 precisam de atenção");

        dispatch.approveSelected();
        assertThat(statuses.getLast()).isEqualTo("Mensagem aprovada. Alterações posteriores exigirão uma nova conferência.");
        assertThat(dispatch.selectedItem.get().state()).isEqualTo(DispatchItemState.APPROVED);
        assertThat(dispatch.isCompletionStepCurrent().get()).isTrue();
        assertThat(dispatch.actionLabel().get()).isEqualTo("Gerar teste seguro");
        assertThat(dispatch.isSendMode().get()).isFalse();
        assertThat(dispatch.canExecuteSelected().get()).isTrue();

        dispatch.executeSelected();
        var done = dispatch.selectedItem.get();
        assertThat(done.state()).isEqualTo(DispatchItemState.ACCEPTED_BY_PROVIDER);
        assertThat(dispatch.hasOutcome().get()).isTrue();
        assertThat(dispatch.outcomeTitle().get()).isEqualTo("Simulação local concluída");
        assertThat(statuses.getLast()).isEqualTo(
                "Simulação local concluída. Nenhum e-mail real foi enviado. Nenhuma conferência de caixa postal é necessária.");
        // A mensagem concluída continua selecionada e o filtro passa para “Concluídas”.
        assertThat(dispatch.queueFilter.get()).isEqualTo(DispatchModel.QueueFilter.COMPLETED);
        assertThat(dispatch.queueSummary().get()).isEqualTo("0 a fazer • 1 concluída(s) • 0 precisam de atenção");
        assertThat(dispatch.workspaceSummary.get()).isEqualTo("Mensagens atuais: 1 • Operações registradas: 1 • Para conferir: 0");
        assertThat(dispatch.canOpenReports().get()).isTrue();

        // Recarregar (nova sessão) preserva o estado persistido no SQLite cifrado.
        dispatch.prepareAnother();
        assertThat(dispatch.selectedItem.get()).isNull();
        assertThat(statuses.getLast()).isEqualTo("Escolha os documentos liberados e o modo da nova mensagem.");
        dispatch.refresh();
        assertThat(dispatch.selectedItem.get().id()).isEqualTo(done.id());
    }

    @Test
    void missingTemplateBlocksAndBatchPreparesEachClientSeparately() throws IOException {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        registerClient("Mercado Bom Preco Ltda", "45.723.174/0001-10", false);
        importAndApprove(payroll("padaria.pdf", "Padaria Sao Joao Ltda", "11.222.333/0001-81"),
                payroll("mercado.pdf", "Mercado Bom Preco Ltda", "45.723.174/0001-10"));
        dispatch.refresh();
        assertThat(dispatch.approvedGroups).hasSize(2);
        assertThat(dispatch.canPrepareBatch().get()).isTrue();

        dispatch.prepareApprovedBatch();
        assertThat(statuses.getLast()).startsWith("2 conjunto(s) foram preparados em mensagens separadas");
        assertThat(dispatch.visibleItems).hasSize(2);
        var blocked = dispatch.visibleItems.stream().filter(i -> i.state() == DispatchItemState.BLOCKED).toList();
        assertThat(blocked).singleElement().satisfies(i -> assertThat(i.clientDisplayName()).isEqualTo("Mercado Bom Preco Ltda"));
        assertThat(dispatch.queueSummary().get()).isEqualTo("2 a fazer • 0 concluída(s) • 1 precisam de atenção");

        // Primeiro em ordem alfabética: Mercado (bloqueado) — etapa “Conferir” com as pendências.
        assertThat(dispatch.selectedItem.get().clientDisplayName()).isEqualTo("Mercado Bom Preco Ltda");
        assertThat(dispatch.isReviewStepCurrent().get()).isTrue();
        assertThat(dispatch.selectedBlocks).extracting(b -> b.code()).contains("SUBJECT_TEMPLATE_MISSING", "BODY_TEMPLATE_MISSING");
        assertThat(dispatch.canApproveSelected().get()).isFalse();

        // Aprovação conjunta deixa o bloqueado de fora.
        assertThat(dispatch.canApproveSelectedBatch().get()).isTrue();
        dispatch.approveSelectedBatch();
        assertThat(statuses.getLast()).isEqualTo(
                "As mensagens prontas deste mês foram aprovadas; as que precisam de correção ficaram de fora.");
        assertThat(dispatch.items).filteredOn(i -> i.state() == DispatchItemState.APPROVED).hasSize(1);

        dispatch.executeSelectedBatch();
        // O item bloqueado continua na sequência; só a mensagem aprovada foi concluída (simulação local).
        assertThat(dispatch.items).filteredOn(i -> i.state() == DispatchItemState.ACCEPTED_BY_PROVIDER).singleElement()
                .satisfies(i -> assertThat(i.clientDisplayName()).startsWith("Padaria"));

        // Busca por referência e por arquivo.
        dispatch.queueFilter.set(DispatchModel.QueueFilter.ALL);
        dispatch.searchText.set("padaria.pdf");
        assertThat(dispatch.visibleItems).singleElement()
                .satisfies(i -> assertThat(i.clientDisplayName()).startsWith("Padaria"));
        dispatch.searchText.set("bom preco");
        assertThat(dispatch.visibleItems).singleElement()
                .satisfies(i -> assertThat(i.clientDisplayName()).isEqualTo("Mercado Bom Preco Ltda"));
        var padaria = dispatch.items.stream().filter(i -> i.clientDisplayName().startsWith("Padaria")).findFirst().orElseThrow();
        dispatch.searchText.set(DispatchModel.shortId(padaria.id()));
        assertThat(dispatch.visibleItems).singleElement().satisfies(i -> assertThat(i.id()).isEqualTo(padaria.id()));
        dispatch.searchText.set("nada disso");
        assertThat(dispatch.visibleItems).isEmpty();
        assertThat(dispatch.queueEmptyMessage().get()).startsWith("Nenhuma mensagem corresponde à busca.");
    }

    @Test
    void batchPreparationRequiresSingleMonthAndFailuresOfferReconciliation() throws IOException {
        registerClient("Padaria Sao Joao Ltda", "11.222.333/0001-81", true);
        importAndApprove(payroll("folha.pdf", "Padaria Sao Joao Ltda", "11.222.333/0001-81"));
        dispatch.refresh();

        shell.selectedMonth().set(shell.months().stream().filter(m -> m.month() == null).findFirst().orElseThrow());
        assertThat(dispatch.canPrepareBatch().get()).isFalse();
        dispatch.prepareApprovedBatch();
        assertThat(statuses.getLast()).isEqualTo(
                "Para preparar vários clientes, selecione um único mês e ano. Isso impede misturar competências.");
        shell.selectedMonth().set(shell.months().stream().filter(m -> m.month() != null && m.month() == 3).findFirst().orElseThrow());

        // Cenário de resultado incerto: nunca afirma que nada aconteceu e oferece conferir sem repetir.
        dispatch.scenario.set(FakeDeliveryScenario.AMBIGUOUS);
        documents.selectedGroup.set(dispatch.approvedGroups.getFirst());
        dispatch.prepareSelected();
        dispatch.approveSelected();
        dispatch.executeSelected();
        assertThat(dispatch.selectedItem.get().state()).isEqualTo(DispatchItemState.AMBIGUOUS);
        assertThat(dispatch.canReconcileSelected().get()).isTrue();
        assertThat(dispatch.canExecuteSelected().get()).isFalse();
        assertThat(dispatch.outcomeTitle().get()).isEqualTo("Teste local com resultado incerto");
        assertThat(dispatch.queueSummary().get()).endsWith("1 precisam de atenção");

        dispatch.reconcileSelected();
        assertThat(statuses.getLast()).isEqualTo("Reconciliação concluída sem repetir a operação de envio.");
        assertThat(dispatch.selectedItem.get().state()).isNotEqualTo(DispatchItemState.SENDING);
    }
}
