package br.com.contadoresassociados.folhas.infrastructure.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentImportException;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentRecognitionOptions;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfRecognitionTest {

    static byte[] pdf(List<String> lines, int pages) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 0; p < pages; p++) {
                var page = new PDPage();
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.setLeading(14);
                    cs.newLineAtOffset(50, 740);
                    for (var line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void payrollIsExtractedClassifiedAndParsed() throws Exception {
        var bytes = pdf(List.of("FOLHA DE PAGAMENTO", "Empregador: Padaria Sao Joao Ltda",
                "Empregador CNPJ: 12.ABC.345/01DE-35", "Competencia: 03/2026", "Total da folha: R$ 12.345,67"), 1);
        var ex = new PdfBoxTextExtractor().extract(new ByteArrayInputStream(bytes), DocumentRecognitionOptions.defaults(),
                CancellationToken.NONE);
        assertThat(ex.pageCount()).isEqualTo(1);
        assertThat(ex.pages().getFirst().words()).isNotEmpty();
        var cls = new DeterministicDocumentClassifier().classify(ex);
        assertThat(cls.documentType()).isEqualTo(RecognizedDocumentType.PAYROLL);
        var parsed = new ProfileDocumentParser().parse(ex, cls);
        assertThat(parsed.findings()).isEmpty();
        assertThat(parsed.fields()).anySatisfy(f -> {
            assertThat(f.role()).isEqualTo(SemanticFieldRole.EMPLOYER_TAX_ID);
            assertThat(f.value()).isEqualTo("12ABC34501DE35");
            assertThat(f.displayValue()).isEqualTo("12.***.***/****-35");
            assertThat(f.evidence().width().signum()).isPositive();
        });
        assertThat(parsed.fields()).anySatisfy(f -> {
            assertThat(f.role()).isEqualTo(SemanticFieldRole.TOTAL_AMOUNT);
            assertThat(f.value()).isEqualTo("12345.67");
        });
    }

    @Test
    void thirteenthCollectsAllEmployeeCpfs() throws Exception {
        var bytes = pdf(List.of("DECIMO TERCEIRO SALARIO", "Empregador CNPJ: 11.222.333/0001-81",
                "Empregado CPF: 529.982.247-25", "Empregado CPF: 111.444.777-35", "Competencia: 12/2025"), 1);
        var ex = new PdfBoxTextExtractor().extract(new ByteArrayInputStream(bytes), DocumentRecognitionOptions.defaults(),
                CancellationToken.NONE);
        var parsed = new ProfileDocumentParser().parse(ex, new DeterministicDocumentClassifier().classify(ex));
        assertThat(parsed.fields().stream().filter(f -> f.role() == SemanticFieldRole.EMPLOYEE_CPF))
                .extracting(f -> f.value()).containsExactly("52998224725", "11144477735");
    }

    @Test
    void limitsAndInvalidPdf() throws Exception {
        var opts = new DocumentRecognitionOptions(25L * 1024 * 1024, 2, 2_000_000, java.time.Duration.ofSeconds(5));
        var three = pdf(List.of("x"), 3);
        assertThatThrownBy(() -> new PdfBoxTextExtractor().extract(new ByteArrayInputStream(three), opts,
                CancellationToken.NONE)).isInstanceOfSatisfying(DocumentImportException.class,
                        e -> assertThat(e.code()).isEqualTo("document.page_limit_exceeded"));
        assertThatThrownBy(() -> new PdfBoxTextExtractor().extract(new ByteArrayInputStream("%PDF-1.7 lixo".getBytes()),
                opts, CancellationToken.NONE)).isInstanceOfSatisfying(DocumentImportException.class,
                        e -> assertThat(e.code()).isEqualTo("document.pdf_invalid_or_protected"));
        var unclassified = new DeterministicDocumentClassifier().classify(new PdfBoxTextExtractor()
                .extract(new ByteArrayInputStream(pdf(List.of("Qualquer coisa"), 1)), opts, CancellationToken.NONE));
        assertThat(unclassified.documentType()).isEqualTo(RecognizedDocumentType.UNCLASSIFIED);
    }

    @Test
    void normalizers() {
        assertThat(ProfileDocumentParser.normalizeDate("05/04/2026")).isEqualTo("2026-04-05");
        assertThat(ProfileDocumentParser.normalizeAmount("R$ 1.500,00")).isEqualTo("1500.00");
        assertThat(ProfileDocumentParser.normalizeTaxId("529.982.247-25")).isEqualTo("52998224725");
        assertThat(DeterministicDocumentClassifier.normalize("Recibo  de Férias\nPeríodo de gozo"))
                .isEqualTo("RECIBO DE FERIAS PERIODO DE GOZO");
    }
}
