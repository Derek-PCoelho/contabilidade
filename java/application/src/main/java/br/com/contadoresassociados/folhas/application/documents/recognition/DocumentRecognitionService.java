package br.com.contadoresassociados.folhas.application.documents.recognition;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import br.com.contadoresassociados.folhas.application.documents.ClientResolver;
import br.com.contadoresassociados.folhas.application.documents.FileFingerprintCache;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentRecognitionResult;
import br.com.contadoresassociados.folhas.contracts.documents.RecognitionConfidence;
import br.com.contadoresassociados.folhas.contracts.documents.RecognitionFinding;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reconhece um PDF: valida assinatura e tamanho, usa cache por SHA-256, extrai texto com limite
 * de tempo (thread virtual cancelada no timeout), classifica, extrai campos e resolve o cliente
 * com os dados minimizados (2.6). Limite de páginas é verificado (o .NET só o repassava ao extrator).
 */
public final class DocumentRecognitionService implements RecognitionPorts.DocumentExtractor {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final BigDecimal HIGH = new BigDecimal("0.85");
    private static final BigDecimal MEDIUM = new BigDecimal("0.60");

    private final RecognitionPorts.PdfTextExtractor extractor;
    private final RecognitionPorts.DocumentClassifier classifier;
    private final RecognitionPorts.DocumentParser parser;
    private final ClientResolver resolver;
    private final RecognitionPorts.RecognitionCache cache;
    private final DocumentRecognitionOptions options;

    public DocumentRecognitionService(RecognitionPorts.PdfTextExtractor extractor,
            RecognitionPorts.DocumentClassifier classifier, RecognitionPorts.DocumentParser parser,
            ClientResolver resolver, RecognitionPorts.RecognitionCache cache, DocumentRecognitionOptions options) {
        this.extractor = extractor;
        this.classifier = classifier;
        this.parser = parser;
        this.resolver = resolver;
        this.cache = cache;
        this.options = options;
    }

    @Override
    public DocumentRecognitionResult recognize(Path file, CancellationToken ct) {
        if (file == null || file.toString().isBlank()) {
            throw new DocumentImportException("document.path_required", "Selecione um arquivo PDF.");
        }
        if (!Files.isRegularFile(file)) {
            throw new DocumentImportException("document.not_found", "O arquivo selecionado não existe.");
        }
        var name = file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new DocumentImportException("document.extension_not_pdf", "Somente arquivos PDF são aceitos.");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new DocumentImportException("document.not_readable", "O arquivo não pôde ser lido.", e);
        }
        if (size <= 0 || size > options.maximumFileSizeBytes()) {
            throw new DocumentImportException("document.size_invalid",
                    "O PDF deve ter até " + options.maximumFileSizeBytes() / 1024 / 1024 + " MB.");
        }
        ct.throwIfCancellationRequested();
        String sha256;
        try (InputStream in = Files.newInputStream(file)) {
            var head = in.readNBytes(PDF_SIGNATURE.length);
            if (!Arrays.equals(head, PDF_SIGNATURE)) {
                throw new DocumentImportException("document.mime_not_pdf",
                        "O conteúdo do arquivo não possui assinatura MIME de PDF.");
            }
            sha256 = FileFingerprintCache.computeSha256(file);
        } catch (IOException e) {
            throw new DocumentImportException("document.not_readable", "O arquivo não pôde ser lido.", e);
        }

        var cached = cache.get(sha256, DocumentRecognitionOptions.ENGINE_VERSION);
        if (cached.isPresent()) {
            var c = cached.get();
            if (c.needsOcr()) {
                return withFile(c, name, true, c.resolution(), c.confidenceScore());
            }
            var resolution = resolve(c.fields());
            var score = confidence(c.documentType() == RecognizedDocumentType.UNCLASSIFIED ? BigDecimal.ZERO
                    : BigDecimal.ONE, c.fields(), resolution);
            return withFile(c, name, true, resolution, score);
        }

        var extraction = extractWithTimeout(file, ct);
        if (extraction.pageCount() > options.maximumPageCount()) {
            throw new DocumentImportException("document.too_many_pages",
                    "O PDF deve ter no máximo " + options.maximumPageCount() + " páginas.");
        }
        if (!extraction.hasText()) {
            var ocr = new DocumentRecognitionResult(name, sha256, "application/pdf", size, extraction.pageCount(),
                    RecognizedDocumentType.UNCLASSIFIED, DocumentRecognitionOptions.ENGINE_VERSION,
                    RecognitionConfidence.LOW, BigDecimal.ZERO, true, false, List.of(),
                    ClientResolutionResult.unresolved("document.needs_ocr"),
                    List.of(new RecognitionFinding("document.needs_ocr",
                            "O PDF não contém texto pesquisável; será necessário OCR manual.", true)));
            cache.put(ocr, DocumentRecognitionOptions.ENGINE_VERSION);
            return ocr;
        }
        ct.throwIfCancellationRequested();
        var classification = classifier.classify(extraction);
        var parsed = parser.parse(extraction, classification);
        var resolution = resolve(parsed.fields());
        var score = confidence(classification.score(), parsed.fields(), resolution);
        var result = new DocumentRecognitionResult(name, sha256, "application/pdf", size, extraction.pageCount(),
                classification.documentType(), classification.profileVersion(), toConfidence(score), score, false,
                false, parsed.fields(), resolution, parsed.findings());
        cache.put(result, DocumentRecognitionOptions.ENGINE_VERSION);
        return result;
    }

    private PdfModel.PdfTextExtraction extractWithTimeout(Path file, CancellationToken outer) {
        var inner = CancellationToken.create();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = exec.submit(() -> {
                try (var in = new BufferedInputStream(Files.newInputStream(file))) {
                    return extractor.extract(in, options, inner);
                }
            });
            try {
                return future.get(options.extractionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                inner.cancel();
                future.cancel(true);
                throw new DocumentImportException("document.extraction_timeout",
                        "A extração excedeu o limite de tempo configurado.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                inner.cancel();
                throw new OperationCancelledException("Operação cancelada.");
            } catch (ExecutionException e) {
                if (outer.isCancellationRequested()) {
                    throw new OperationCancelledException("Operação cancelada.");
                }
                var cause = e.getCause();
                if (cause instanceof DocumentImportException die) {
                    throw die;
                }
                throw new DocumentImportException("document.pdf_invalid",
                        "O PDF está corrompido, protegido por senha ou não pôde ser lido.", cause);
            }
        }
    }

    private ClientResolutionResult resolve(List<RecognizedField> fields) {
        return resolver.resolve(ClientResolver.minimize(fields));
    }

    static BigDecimal confidence(BigDecimal classification, List<RecognizedField> fields,
            ClientResolutionResult resolution) {
        var fieldScore = fields.isEmpty() ? BigDecimal.ZERO
                : fields.stream().map(RecognizedField::confidence).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(fields.size()), 10, RoundingMode.HALF_EVEN);
        var resolutionScore = resolution.isResolved() ? resolution.confidence() : BigDecimal.ZERO;
        var total = classification.multiply(new BigDecimal("0.45")).add(fieldScore.multiply(new BigDecimal("0.35")))
                .add(resolutionScore.multiply(new BigDecimal("0.20")));
        total = total.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return total.setScale(2, RoundingMode.HALF_EVEN);
    }

    static RecognitionConfidence toConfidence(BigDecimal score) {
        return score.compareTo(HIGH) >= 0 ? RecognitionConfidence.HIGH
                : score.compareTo(MEDIUM) >= 0 ? RecognitionConfidence.MEDIUM : RecognitionConfidence.LOW;
    }

    private static DocumentRecognitionResult withFile(DocumentRecognitionResult c, String name, boolean fromCache,
            ClientResolutionResult resolution, BigDecimal score) {
        return new DocumentRecognitionResult(name, c.sha256(), c.mimeType(), c.fileSizeBytes(), c.pageCount(),
                c.documentType(), c.profileVersion(), toConfidence(score), score, c.needsOcr(), fromCache, c.fields(),
                resolution, c.findings());
    }
}
