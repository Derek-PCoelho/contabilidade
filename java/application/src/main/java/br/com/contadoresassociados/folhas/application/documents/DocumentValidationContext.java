package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DocumentValidationContext(
        ReviewDocument document,
        DocumentReviewProfile profile,
        LocalDate accountingDate,
        OffsetDateTime evaluatedAtUtc,
        FileFingerprintCache fingerprints) {
}
