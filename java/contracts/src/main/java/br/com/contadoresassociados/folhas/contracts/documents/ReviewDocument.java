package br.com.contadoresassociados.folhas.contracts.documents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReviewDocument(
        UUID id,
        String localPath,
        String fileName,
        String sha256,
        long fileSizeBytes,
        int pageCount,
        RecognizedDocumentType documentType,
        String profileVersion,
        UUID clientId,
        UUID establishmentId,
        String clientDisplayName,
        String clientTaxIdMasked,
        ClientResolutionMethod resolutionMethod,
        BigDecimal resolutionConfidence,
        List<ClientResolutionCandidate> clientAlternatives,
        List<String> resolutionBlockers,
        List<RecognizedField> fields,
        List<RecognitionFinding> recognitionFindings,
        DocumentPeriod period,
        String semanticDuplicateKey,
        ReviewDocumentState state,
        long revision,
        UUID groupId,
        List<ValidationFinding> findings,
        OffsetDateTime importedAtUtc,
        OffsetDateTime validatedAtUtc,
        DocumentPeriod periodOverride,
        // Pendência 2.1: trava persistente de "cliente mudou". Enquanto não for nula, o documento
        // fica bloqueado até uma confirmação humana explícita, mesmo em revalidações seguintes.
        UUID pendingClientChangeFrom) {

    public ReviewDocument {
        clientAlternatives = clientAlternatives == null ? List.of() : List.copyOf(clientAlternatives);
        resolutionBlockers = resolutionBlockers == null ? List.of() : List.copyOf(resolutionBlockers);
        fields = fields == null ? List.of() : List.copyOf(fields);
        recognitionFindings = recognitionFindings == null ? List.of() : List.copyOf(recognitionFindings);
        findings = findings == null ? List.of() : List.copyOf(findings);
        resolutionConfidence = resolutionConfidence == null ? BigDecimal.ZERO : resolutionConfidence;
    }

    @JsonIgnore
    public DocumentPeriod effectivePeriod() {
        return periodOverride != null ? periodOverride : period;
    }

    @JsonIgnore
    public boolean preventsApproval() {
        return findings.stream().anyMatch(ValidationFinding::preventsApproval);
    }

    @JsonIgnore
    public long blockingFindingCount() {
        return findings.stream().filter(ValidationFinding::preventsApproval).count();
    }

    @JsonIgnore
    public String reviewSummary() {
        return switch (state) {
            case DUPLICATE -> "Duplicado — aprovação proibida";
            case BLOCKED -> "Bloqueado — " + blockingFindingCount() + " pendência(s)";
            case APPROVED -> "Aprovado para preparar a mensagem";
            case GROUPED -> "Elegível e agrupado";
            case READY -> "Elegível para agrupamento";
        };
    }

    public ReviewDocument withState(ReviewDocumentState newState, UUID newGroupId) {
        return new ReviewDocument(id, localPath, fileName, sha256, fileSizeBytes, pageCount, documentType,
                profileVersion, clientId, establishmentId, clientDisplayName, clientTaxIdMasked, resolutionMethod,
                resolutionConfidence, clientAlternatives, resolutionBlockers, fields, recognitionFindings, period,
                semanticDuplicateKey, newState, revision, newGroupId, findings, importedAtUtc, validatedAtUtc,
                periodOverride, pendingClientChangeFrom);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.localPath = localPath;
        b.fileName = fileName;
        b.sha256 = sha256;
        b.fileSizeBytes = fileSizeBytes;
        b.pageCount = pageCount;
        b.documentType = documentType;
        b.profileVersion = profileVersion;
        b.clientId = clientId;
        b.establishmentId = establishmentId;
        b.clientDisplayName = clientDisplayName;
        b.clientTaxIdMasked = clientTaxIdMasked;
        b.resolutionMethod = resolutionMethod;
        b.resolutionConfidence = resolutionConfidence;
        b.clientAlternatives = clientAlternatives;
        b.resolutionBlockers = resolutionBlockers;
        b.fields = fields;
        b.recognitionFindings = recognitionFindings;
        b.period = period;
        b.semanticDuplicateKey = semanticDuplicateKey;
        b.state = state;
        b.revision = revision;
        b.groupId = groupId;
        b.findings = findings;
        b.importedAtUtc = importedAtUtc;
        b.validatedAtUtc = validatedAtUtc;
        b.periodOverride = periodOverride;
        b.pendingClientChangeFrom = pendingClientChangeFrom;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private String localPath;
        private String fileName;
        private String sha256;
        private long fileSizeBytes;
        private int pageCount;
        private RecognizedDocumentType documentType;
        private String profileVersion;
        private UUID clientId;
        private UUID establishmentId;
        private String clientDisplayName;
        private String clientTaxIdMasked;
        private ClientResolutionMethod resolutionMethod;
        private BigDecimal resolutionConfidence;
        private List<ClientResolutionCandidate> clientAlternatives;
        private List<String> resolutionBlockers;
        private List<RecognizedField> fields;
        private List<RecognitionFinding> recognitionFindings;
        private DocumentPeriod period;
        private String semanticDuplicateKey;
        private ReviewDocumentState state;
        private long revision;
        private UUID groupId;
        private List<ValidationFinding> findings;
        private OffsetDateTime importedAtUtc;
        private OffsetDateTime validatedAtUtc;
        private DocumentPeriod periodOverride;
        private UUID pendingClientChangeFrom;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder localPath(String value) {
            this.localPath = value;
            return this;
        }

        public Builder fileName(String value) {
            this.fileName = value;
            return this;
        }

        public Builder sha256(String value) {
            this.sha256 = value;
            return this;
        }

        public Builder fileSizeBytes(long value) {
            this.fileSizeBytes = value;
            return this;
        }

        public Builder pageCount(int value) {
            this.pageCount = value;
            return this;
        }

        public Builder documentType(RecognizedDocumentType value) {
            this.documentType = value;
            return this;
        }

        public Builder profileVersion(String value) {
            this.profileVersion = value;
            return this;
        }

        public Builder clientId(UUID value) {
            this.clientId = value;
            return this;
        }

        public Builder establishmentId(UUID value) {
            this.establishmentId = value;
            return this;
        }

        public Builder clientDisplayName(String value) {
            this.clientDisplayName = value;
            return this;
        }

        public Builder clientTaxIdMasked(String value) {
            this.clientTaxIdMasked = value;
            return this;
        }

        public Builder resolutionMethod(ClientResolutionMethod value) {
            this.resolutionMethod = value;
            return this;
        }

        public Builder resolutionConfidence(BigDecimal value) {
            this.resolutionConfidence = value;
            return this;
        }

        public Builder clientAlternatives(List<ClientResolutionCandidate> value) {
            this.clientAlternatives = value;
            return this;
        }

        public Builder resolutionBlockers(List<String> value) {
            this.resolutionBlockers = value;
            return this;
        }

        public Builder fields(List<RecognizedField> value) {
            this.fields = value;
            return this;
        }

        public Builder recognitionFindings(List<RecognitionFinding> value) {
            this.recognitionFindings = value;
            return this;
        }

        public Builder period(DocumentPeriod value) {
            this.period = value;
            return this;
        }

        public Builder semanticDuplicateKey(String value) {
            this.semanticDuplicateKey = value;
            return this;
        }

        public Builder state(ReviewDocumentState value) {
            this.state = value;
            return this;
        }

        public Builder revision(long value) {
            this.revision = value;
            return this;
        }

        public Builder groupId(UUID value) {
            this.groupId = value;
            return this;
        }

        public Builder findings(List<ValidationFinding> value) {
            this.findings = value;
            return this;
        }

        public Builder importedAtUtc(OffsetDateTime value) {
            this.importedAtUtc = value;
            return this;
        }

        public Builder validatedAtUtc(OffsetDateTime value) {
            this.validatedAtUtc = value;
            return this;
        }

        public Builder periodOverride(DocumentPeriod value) {
            this.periodOverride = value;
            return this;
        }

        public Builder pendingClientChangeFrom(UUID value) {
            this.pendingClientChangeFrom = value;
            return this;
        }

        public ReviewDocument build() {
            return new ReviewDocument(id, localPath, fileName, sha256, fileSizeBytes, pageCount, documentType, profileVersion, clientId, establishmentId, clientDisplayName, clientTaxIdMasked, resolutionMethod, resolutionConfidence, clientAlternatives, resolutionBlockers, fields, recognitionFindings, period, semanticDuplicateKey, state, revision, groupId, findings, importedAtUtc, validatedAtUtc, periodOverride, pendingClientChangeFrom);
        }
    }
}
