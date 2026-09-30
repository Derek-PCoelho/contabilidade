package br.com.contadoresassociados.folhas.application.documents.recognition;

import br.com.contadoresassociados.folhas.contracts.documents.RecognitionFinding;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/** Modelo neutro de extração de PDF, classificação e parsing. */
public final class PdfModel {

    private PdfModel() {
    }

    public record PdfWord(String text, int pageNumber, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
    }

    public record ExtractedPdfPage(int pageNumber, String text, List<PdfWord> words) {
        public ExtractedPdfPage {
            words = words == null ? List.of() : List.copyOf(words);
        }
    }

    public record PdfTextExtraction(int pageCount, List<ExtractedPdfPage> pages) {
        public PdfTextExtraction {
            pages = List.copyOf(pages);
        }

        public String fullText() {
            return pages.stream().map(ExtractedPdfPage::text).collect(Collectors.joining("\n"));
        }

        public boolean hasText() {
            return pages.stream().anyMatch(p -> p.text() != null && !p.text().isBlank());
        }
    }

    public record DocumentClassification(RecognizedDocumentType documentType, String profileVersion, BigDecimal score,
            List<String> matchedAnchors) {
        public DocumentClassification {
            matchedAnchors = List.copyOf(matchedAnchors);
        }
    }

    public record ParsedDocument(List<RecognizedField> fields, List<RecognitionFinding> findings) {
        public ParsedDocument {
            fields = List.copyOf(fields);
            findings = List.copyOf(findings);
        }
    }
}
