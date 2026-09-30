package br.com.contadoresassociados.folhas.application.documents.recognition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.documents.ClientResolver;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentRecognitionServiceTest {

    @TempDir
    Path dir;

    final List<ClientResolutionRequest> resolverCalls = new ArrayList<>();
    final HashMap<String, DocumentRecognitionResult> cache = new HashMap<>();

    DocumentRecognitionService service(RecognitionPorts.PdfTextExtractor extractor, DocumentRecognitionOptions opts) {
        ClientResolver resolver = r -> {
            resolverCalls.add(r);
            return new ClientResolutionResult(UUID.randomUUID(), null, "Empresa", "**", ClientResolutionMethod.EXACT_CLIENT_TAX_ID,
                    BigDecimal.ONE, List.of(), List.of(), List.of());
        };
        RecognitionPorts.RecognitionCache c = new RecognitionPorts.RecognitionCache() {
            @Override public Optional<DocumentRecognitionResult> get(String sha, String v) {
                return Optional.ofNullable(cache.get(sha));
            }
            @Override public void put(DocumentRecognitionResult r, String v) { cache.put(r.sha256(), r); }
        };
        var evidence = new EvidenceBox(1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                "Empregado CPF 529.982.247-25 salário");
        return new DocumentRecognitionService(extractor,
                e -> new PdfModel.DocumentClassification(RecognizedDocumentType.PAYROLL, "p1", BigDecimal.ONE, List.of()),
                (e, k) -> new PdfModel.ParsedDocument(List.of(
                        new RecognizedField("CNPJ", "11222333000181", "11.222.333/0001-81",
                                SemanticFieldRole.EMPLOYER_TAX_ID, BigDecimal.ONE, evidence),
                        new RecognizedField("CPF", "52998224725", "529.982.247-25", SemanticFieldRole.EMPLOYEE_CPF,
                                BigDecimal.ONE, evidence)), List.of()),
                resolver, c, opts);
    }

    static RecognitionPorts.PdfTextExtractor text(String t) {
        return (in, o, ct) -> new PdfModel.PdfTextExtraction(1, List.of(new PdfModel.ExtractedPdfPage(1, t, List.of())));
    }

    @Test
    void recognizesMinimizesAndCaches() throws Exception {
        var f = Files.writeString(dir.resolve("a.pdf"), "%PDF-1.7 conteudo");
        var svc = service(text("FOLHA"), DocumentRecognitionOptions.defaults());
        var r = svc.recognize(f, CancellationToken.NONE);
        assertThat(r.documentType()).isEqualTo(RecognizedDocumentType.PAYROLL);
        assertThat(r.confidence()).isEqualTo(RecognitionConfidence.HIGH);
        assertThat(resolverCalls.getFirst().fields()).singleElement().satisfies(fd -> {
            assertThat(fd.role()).isEqualTo(SemanticFieldRole.EMPLOYER_TAX_ID);
            assertThat(fd.evidence().snippet()).doesNotContain("529.982");
        });
        assertThat(svc.recognize(f, CancellationToken.NONE).fromCache()).isTrue();
    }

    @Test
    void rejectsNonPdfAndFlagsOcr() throws Exception {
        var fake = Files.writeString(dir.resolve("b.pdf"), "GIF89a");
        var svc = service(text("   "), DocumentRecognitionOptions.defaults());
        assertThatThrownBy(() -> svc.recognize(fake, CancellationToken.NONE))
                .isInstanceOfSatisfying(DocumentImportException.class,
                        e -> assertThat(e.code()).isEqualTo("document.mime_not_pdf"));
        var scanned = Files.writeString(dir.resolve("c.pdf"), "%PDF-1.4 imagem");
        assertThat(svc.recognize(scanned, CancellationToken.NONE).needsOcr()).isTrue();
    }

    @Test
    void extractionTimeoutIsReported() throws Exception {
        var f = Files.writeString(dir.resolve("d.pdf"), "%PDF-1.7 lento");
        var opts = new DocumentRecognitionOptions(1024 * 1024, 100, 1000, Duration.ofMillis(100));
        var svc = service((in, o, ct) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                throw new java.io.InterruptedIOException("cancelado");
            }
            return null;
        }, opts);
        assertThatThrownBy(() -> svc.recognize(f, CancellationToken.NONE))
                .isInstanceOfSatisfying(DocumentImportException.class,
                        e -> assertThat(e.code()).isEqualTo("document.extraction_timeout"));
    }
}
