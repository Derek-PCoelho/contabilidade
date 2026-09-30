package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewGroupState;
import br.com.contadoresassociados.folhas.desktop.DesktopServices;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
 * Seção Documentos de ponta a ponta: PDF real → reconhecimento (PDFBox) → cliente do cadastro
 * local → revisão → liberação, com os serviços reais do Desktop sobre SQLite cifrado.
 */
class DocumentsModelTest {

    private static final String CNPJ = "11.222.333/0001-81";

    @TempDir
    Path temp;
    private DesktopServices services;
    private ShellState shell;
    private DocumentsModel documents;
    private ClientsModel clients;
    private final List<String> statuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        services = DesktopServices.create(temp.resolve("data"), true);
        shell = new ShellState(2026, 3);
        shell.statusMessage().addListener((obs, old, now) -> statuses.add(now));
        documents = new DocumentsModel(services.review(), services.recognition(), shell, UiTasks.synchronous(),
                services.documentArchiveDirectory());
        clients = new ClientsModel(services.catalog(), UiTasks.synchronous(), shell::status, documents::reload);
    }

    @AfterEach
    void tearDown() {
        services.close();
    }

    private void registerClient() {
        clients.newClient();
        clients.legalName.set("Padaria São João Ltda");
        clients.primaryTaxId.set(CNPJ);
        clients.newRecipientName.set("Financeiro");
        clients.newRecipientEmail.set("financeiro@padaria.com.br");
        clients.addRecipient();
        clients.saveClient();
        assertThat(clients.feedbackIsError.get()).as(clients.feedbackMessage.get()).isFalse();
    }

    private Path payroll(String name, String competence, String cnpj) throws IOException {
        var file = temp.resolve(name);
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 740);
                for (var line : List.of("FOLHA DE PAGAMENTO", "Empregador: Padaria Sao Joao Ltda", "Empregador CNPJ: " + cnpj,
                        "Competencia: " + competence, "Total da folha: R$ 12.345,67", "Arquivo " + name)) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void importsRecognizesArchivesAndApproves() throws IOException {
        registerClient();
        documents.loadIfNeeded();
        documents.importFiles(List.of(payroll("folha-marco.pdf", "03/2026", CNPJ)));

        assertThat(documents.recognizing.get()).isFalse();
        assertThat(documents.documentImportSummary.get()).startsWith("1 documento(s) importado(s), reconhecido(s) e organizado(s)");
        assertThat(documents.visibleDocuments).singleElement().satisfies(d -> {
            assertThat(d.clientDisplayName()).isEqualTo("Padaria São João Ltda");
            assertThat(d.state()).isIn(ReviewDocumentState.GROUPED, ReviewDocumentState.READY);
            // Acervo organizado por ano/mês, nome com prefixo do SHA-256.
            assertThat(Path.of(d.localPath())).startsWith(services.documentArchiveDirectory().resolve("2026").resolve("03"));
            assertThat(Path.of(d.localPath()).getFileName().toString()).matches("folha-marco-[0-9a-f]{12}\\.pdf");
        });
        assertThat(documents.selectedDocument.get()).isNotNull();
        assertThat(documents.clientSummary().get()).startsWith("Cliente identificado: Padaria São João Ltda");
        assertThat(documents.guidance().get()).startsWith("Documento conferido");
        assertThat(documents.selectedGroup.get()).isNotNull();
        assertThat(documents.isApprovalStepCurrent().get()).isTrue();
        assertThat(shell.visibleDocumentCount().get()).isEqualTo(1);

        documents.approveSelectedGroup();
        assertThat(statuses.getLast()).isEqualTo("Conjunto liberado para preparar a mensagem. Nenhum e-mail foi enviado.");
        assertThat(documents.selectedGroup.get().state()).isEqualTo(ReviewGroupState.APPROVED);
        assertThat(shell.approvedGroupCount().get()).isEqualTo(1);
        assertThat(documents.canContinueToDispatch().get()).isTrue();
    }

    @Test
    void sameContentIsNotDuplicatedAndOtherFormatsAreSummarized() throws IOException {
        registerClient();
        documents.loadIfNeeded();
        var folder = Files.createDirectories(temp.resolve("entrada"));
        Files.copy(payroll("a.pdf", "03/2026", CNPJ), folder.resolve("a.pdf"));
        Files.writeString(folder.resolve("planilha.xlsx"), "x");
        Files.writeString(folder.resolve("nota.docx"), "x");

        documents.inputFolder.set(folder.toString());
        documents.importInputFolder();
        assertThat(documents.documents).hasSize(1);
        assertThat(documents.documentImportSummary.get())
                .endsWith("2 arquivos não compatíveis foram recusados e preservados na pasta (DOCX: 1; XLSX: 1).");

        documents.importInputFolder();
        assertThat(documents.documents).hasSize(1);
        assertThat(documents.documentImportSummary.get()).contains("1 já estava(m) importado(s) e não foi(ram) duplicado(s)");
        try (Stream<Path> archived = Files.walk(services.documentArchiveDirectory())) {
            assertThat(archived.filter(Files::isRegularFile)).hasSize(1);
        }
    }

    @Test
    void unknownClientBlocksAndInvalidPdfIsRejected() throws IOException {
        documents.loadIfNeeded();
        var broken = temp.resolve("quebrado.pdf");
        Files.writeString(broken, "%PDF-1.7 lixo");
        documents.importFiles(List.of(payroll("sem-cadastro.pdf", "03/2026", CNPJ), broken));

        assertThat(documents.documentImportSummary.get()).startsWith("1 reconhecido(s); 1 rejeitado(s): quebrado.pdf:");
        var doc = documents.selectedDocument.get();
        assertThat(doc.state()).isEqualTo(ReviewDocumentState.BLOCKED);
        assertThat(documents.clientSummary().get()).startsWith("Cliente ainda não identificado");
        assertThat(documents.canApproveSelectedGroup().get()).isFalse();
        assertThat(shell.blockedDocumentCount().get()).isEqualTo(1);

        // Cadastrar o cliente revalida a revisão na mesma transação e a tela é recarregada.
        registerClient();
        assertThat(documents.selectedDocument.get().clientDisplayName()).isEqualTo("Padaria São João Ltda");
        assertThat(documents.selectedDocument.get().state()).isNotEqualTo(ReviewDocumentState.BLOCKED);
    }

    @Test
    void periodCorrectionRestoreAndRemovalRequireReasons() throws IOException {
        registerClient();
        documents.loadIfNeeded();
        documents.importFiles(List.of(payroll("folha.pdf", "03/2026", CNPJ)));

        documents.correctionMonth.set(documents.correctionMonths().get(3));
        documents.correctionReason.set("curto");
        documents.correctPeriod();
        assertThat(statuses.getLast()).isEqualTo("Explique a correção da competência com pelo menos 10 caracteres.");

        documents.correctionReason.set("Competência impressa errada no PDF");
        documents.correctPeriod();
        assertThat(statuses.getLast()).isEqualTo("Competência corrigida para 04/2026, reagrupada e registrada no histórico.");
        // O documento saiu de março: a seleção acompanha o filtro da competência.
        assertThat(documents.visibleDocuments).isEmpty();
        assertThat(documents.hasHiddenDocuments().get()).isTrue();
        documents.showAllPeriods();
        assertThat(documents.visibleDocuments).hasSize(1);
        documents.selectedDocument.set(documents.visibleDocuments.getFirst());
        assertThat(documents.canRestorePeriod().get()).isTrue();

        documents.correctionReason.set("Voltar ao que foi lido no PDF");
        documents.restorePeriod();
        assertThat(documents.selectedDocument.get().periodOverride()).isNull();

        documents.beginRemove();
        assertThat(documents.removalConfirmation.get()).isTrue();
        documents.removalReason.set("importado por engano");
        documents.confirmRemove();
        assertThat(documents.documents).isEmpty();
        assertThat(statuses.getLast()).startsWith("Documento retirado da revisão. O PDF foi preservado no acervo");
    }

    @Test
    void bulkApprovalNeedsSingleMonth() throws IOException {
        registerClient();
        documents.loadIfNeeded();
        documents.showAllPeriods();
        documents.approveAllEligible();
        assertThat(statuses.getLast()).startsWith("Para liberar vários conjuntos, selecione um único mês e ano.");
    }

    @Test
    void unsupportedSummaryMatchesDotNet() {
        assertThat(DocumentsModel.unsupportedSummary(List.of(Path.of("a.txt"))))
                .isEqualTo("1 arquivo não compatível foi recusado e preservado na pasta (TXT: 1).");
        assertThat(DocumentsModel.unsupportedSummary(List.of(Path.of("LEIAME"), Path.of("b.TXT"), Path.of("c.txt"))))
                .isEqualTo("3 arquivos não compatíveis foram recusados e preservados na pasta (SEM EXTENSÃO: 1; TXT: 2).");
    }
}
