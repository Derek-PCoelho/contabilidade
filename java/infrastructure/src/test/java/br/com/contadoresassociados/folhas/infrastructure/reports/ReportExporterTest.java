package br.com.contadoresassociados.folhas.infrastructure.reports;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchReportFilter;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportExporterTest {

    static final Instant NOW = Instant.parse("2026-09-30T15:30:00Z");

    @TempDir
    Path dir;

    @Test
    void formulaInjectionIsNeutralizedIncludingTabAndCr() {
        assertThat(ReportText.sanitize("=HYPERLINK(\"http://x\")")).startsWith("'=");
        assertThat(ReportText.sanitize("\t=1+1")).startsWith("'\t");
        assertThat(ReportText.sanitize("\r=1")).startsWith("'\r");
        assertThat(ReportText.sanitize("＝1")).startsWith("'");
        assertThat(ReportText.sanitize("Folha 03/2026")).isEqualTo("Folha 03/2026");
    }

    @Test
    void exportsXlsxPdfAndCsvWithParityStructure() throws Exception {
        var now = NOW.atOffset(ZoneOffset.UTC);
        var docs = new ArrayList<ReviewDocument>();
        var period = new DocumentPeriod(DocumentPeriodKind.MONTHLY, 3, 2026, null, null, null, "03/2026");
        var groupId = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            docs.add(new ReviewDocument(UUID.randomUUID(), "/tmp/f" + i + ".pdf", "folha-" + i + ".pdf", "ab".repeat(32), 10,
                    1, RecognizedDocumentType.PAYROLL, "p1", UUID.randomUUID(), null,
                    i == 0 ? "=HYPERLINK(\"http://mal\")" : "Cliente " + i + " Comércio de Alimentos Ltda", "**",
                    ClientResolutionMethod.NONE, BigDecimal.ONE, List.of(), List.of(), List.of(), List.of(), period, "k" + i,
                    i % 7 == 0 ? ReviewDocumentState.DUPLICATE : ReviewDocumentState.APPROVED, 1, groupId, List.of(), now,
                    now, null, null));
        }
        var review = new DocumentReviewWorkspace("org:1|local", docs, List.of(), List.of(), 1);
        var item = new DispatchItem(UUID.randomUUID(), UUID.randomUUID(), groupId, docs.get(1).clientId(),
                docs.get(1).clientDisplayName(), null, "03/2026", DispatchOperationMode.TEST, FakeDeliveryScenario.SUCCESS,
                null, DispatchItemState.AMBIGUOUS, 1, new RenderedMessageSnapshot(UUID.randomUUID(), 1, "A", UUID.randomUUID(),
                        1, "C", "fake://local", List.of(), List.of(), List.of("teste@x.com"), List.of(), "Assunto", "t",
                        "<p>t</p>", List.of(new DispatchAttachmentSnapshot(docs.get(1).id(), "/tmp/f1.pdf", "folha-1.pdf",
                                "ab".repeat(32), 10, RecognizedDocumentType.PAYROLL)), "fp", now),
                null, List.of(), now, now);
        var dispatch = new DispatchWorkspace("org:1|local", List.of(), List.of(item), List.of(), List.of(), 1);

        var result = new StandardDispatchReportExporter(Clock.fixed(NOW), ZoneId.of("America/Sao_Paulo"))
                .export(dispatch, review, DispatchReportFilter.month(2026, 3), dir);

        assertThat(Path.of(result.xlsxPath()).getFileName().toString()).isEqualTo("folhas-da-michelly-20260930-153000.xlsx");
        try (var wb = new XSSFWorkbook(Files.newInputStream(Path.of(result.xlsxPath())))) {
            assertThat(List.of(wb.getSheetAt(0).getSheetName(), wb.getSheetAt(1).getSheetName(),
                    wb.getSheetAt(2).getSheetName(), wb.getSheetAt(3).getSheetName(), wb.getSheetAt(4).getSheetName()))
                    .containsExactly("Resumo", "Itens", "Erros", "Duplicados", "Auditoria");
            var items = wb.getSheet("Itens");
            assertThat(items.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Data e hora (UTC)");
            assertThat(items.isColumnHidden(1)).isTrue();
            assertThat(items.getTables()).singleElement().satisfies(t -> assertThat(t.getName()).isEqualTo("tblItens"));
            assertThat(items.getLastRowNum()).isEqualTo(4 + 60);
            var allCells = new ArrayList<String>();
            items.forEach(r -> r.forEach(c -> allCells.add(c.toString())));
            assertThat(allCells).noneMatch(v -> v.startsWith("="));
            assertThat(wb.getSheet("Resumo").getRow(5).getCell(0).getStringCellValue())
                    .isEqualTo("Competência 03/2026 • todos os clientes");
        }
        try (var pdf = Loader.loadPDF(Path.of(result.pdfPath()).toFile())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("FOLHAS DA MICHELLY", "Resumo por cliente", "Situação das comunicações",
                    "Teste local com resultado incerto", "Gerado em 30/09/2026 às 12:30", "1/" + pdf.getNumberOfPages());
        }
        assertThat(result.csvPaths()).hasSize(5);
        var csv = Files.readString(Path.of(result.csvPaths().get(1)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF\"Data e hora (UTC)\";\"Operador\"");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://mal\"\")\"");
        assertThat(Files.readAllLines(Path.of(result.csvPaths().get(3)))).hasSize(1 + 9);
    }
}
