package br.com.contadoresassociados.folhas.contracts.documents;

import java.math.BigDecimal;
import java.util.List;

public record DocumentRecognitionResult(
        String fileName,
        String sha256,
        String mimeType,
        long fileSizeBytes,
        int pageCount,
        RecognizedDocumentType documentType,
        String profileVersion,
        RecognitionConfidence confidence,
        BigDecimal confidenceScore,
        boolean needsOcr,
        boolean fromCache,
        List<RecognizedField> fields,
        ClientResolutionResult resolution,
        List<RecognitionFinding> findings) {

    public DocumentRecognitionResult {
        fields = fields == null ? List.of() : List.copyOf(fields);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
