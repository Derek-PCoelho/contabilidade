package br.com.contadoresassociados.folhas.contracts.documents;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ValidationFinding(
        UUID id,
        String ruleCode,
        ValidationSeverity severity,
        String message,
        String fieldKey,
        boolean isResolved,
        FindingResolutionType resolutionType,
        String resolvedBy,
        String resolutionNote,
        OffsetDateTime createdAtUtc,
        OffsetDateTime resolvedAtUtc) {

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean preventsApproval() {
        return !isResolved && (severity == ValidationSeverity.ERROR || severity == ValidationSeverity.BLOCKER);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.ruleCode = ruleCode;
        b.severity = severity;
        b.message = message;
        b.fieldKey = fieldKey;
        b.isResolved = isResolved;
        b.resolutionType = resolutionType;
        b.resolvedBy = resolvedBy;
        b.resolutionNote = resolutionNote;
        b.createdAtUtc = createdAtUtc;
        b.resolvedAtUtc = resolvedAtUtc;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private String ruleCode;
        private ValidationSeverity severity;
        private String message;
        private String fieldKey;
        private boolean isResolved;
        private FindingResolutionType resolutionType;
        private String resolvedBy;
        private String resolutionNote;
        private OffsetDateTime createdAtUtc;
        private OffsetDateTime resolvedAtUtc;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder ruleCode(String value) {
            this.ruleCode = value;
            return this;
        }

        public Builder severity(ValidationSeverity value) {
            this.severity = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder fieldKey(String value) {
            this.fieldKey = value;
            return this;
        }

        public Builder isResolved(boolean value) {
            this.isResolved = value;
            return this;
        }

        public Builder resolutionType(FindingResolutionType value) {
            this.resolutionType = value;
            return this;
        }

        public Builder resolvedBy(String value) {
            this.resolvedBy = value;
            return this;
        }

        public Builder resolutionNote(String value) {
            this.resolutionNote = value;
            return this;
        }

        public Builder createdAtUtc(OffsetDateTime value) {
            this.createdAtUtc = value;
            return this;
        }

        public Builder resolvedAtUtc(OffsetDateTime value) {
            this.resolvedAtUtc = value;
            return this;
        }

        public ValidationFinding build() {
            return new ValidationFinding(id, ruleCode, severity, message, fieldKey, isResolved, resolutionType, resolvedBy, resolutionNote, createdAtUtc, resolvedAtUtc);
        }
    }
}
