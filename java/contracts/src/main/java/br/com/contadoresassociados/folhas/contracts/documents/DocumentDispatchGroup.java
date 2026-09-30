package br.com.contadoresassociados.folhas.contracts.documents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentDispatchGroup(
        UUID id,
        String groupingKey,
        String groupingPolicyCode,
        String groupingPolicyVersion,
        UUID clientId,
        UUID establishmentId,
        String clientDisplayName,
        String periodKey,
        String periodLabel,
        ReviewGroupState state,
        long revision,
        List<UUID> documentIds,
        List<ValidationFinding> findings,
        GroupApprovalSnapshot approvalSnapshot,
        OffsetDateTime createdAtUtc,
        OffsetDateTime updatedAtUtc) {

    public DocumentDispatchGroup {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    @JsonIgnore
    public boolean isApproved() {
        return approvalSnapshot != null && state == ReviewGroupState.APPROVED;
    }

    @JsonIgnore
    public boolean preventsApproval() {
        return findings.stream().anyMatch(ValidationFinding::preventsApproval);
    }

    @JsonIgnore
    public String approvalSummary() {
        return switch (state) {
            case APPROVED -> "Aprovado no aplicativo";
            case BLOCKED -> "Bloqueado — revisar pendências";
            case READY_FOR_REVIEW -> "Pronto para revisão e aprovação";
            case BUILDING -> "Em formação";
        };
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.groupingKey = groupingKey;
        b.groupingPolicyCode = groupingPolicyCode;
        b.groupingPolicyVersion = groupingPolicyVersion;
        b.clientId = clientId;
        b.establishmentId = establishmentId;
        b.clientDisplayName = clientDisplayName;
        b.periodKey = periodKey;
        b.periodLabel = periodLabel;
        b.state = state;
        b.revision = revision;
        b.documentIds = documentIds;
        b.findings = findings;
        b.approvalSnapshot = approvalSnapshot;
        b.createdAtUtc = createdAtUtc;
        b.updatedAtUtc = updatedAtUtc;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private String groupingKey;
        private String groupingPolicyCode;
        private String groupingPolicyVersion;
        private UUID clientId;
        private UUID establishmentId;
        private String clientDisplayName;
        private String periodKey;
        private String periodLabel;
        private ReviewGroupState state;
        private long revision;
        private List<UUID> documentIds;
        private List<ValidationFinding> findings;
        private GroupApprovalSnapshot approvalSnapshot;
        private OffsetDateTime createdAtUtc;
        private OffsetDateTime updatedAtUtc;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder groupingKey(String value) {
            this.groupingKey = value;
            return this;
        }

        public Builder groupingPolicyCode(String value) {
            this.groupingPolicyCode = value;
            return this;
        }

        public Builder groupingPolicyVersion(String value) {
            this.groupingPolicyVersion = value;
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

        public Builder periodKey(String value) {
            this.periodKey = value;
            return this;
        }

        public Builder periodLabel(String value) {
            this.periodLabel = value;
            return this;
        }

        public Builder state(ReviewGroupState value) {
            this.state = value;
            return this;
        }

        public Builder revision(long value) {
            this.revision = value;
            return this;
        }

        public Builder documentIds(List<UUID> value) {
            this.documentIds = value;
            return this;
        }

        public Builder findings(List<ValidationFinding> value) {
            this.findings = value;
            return this;
        }

        public Builder approvalSnapshot(GroupApprovalSnapshot value) {
            this.approvalSnapshot = value;
            return this;
        }

        public Builder createdAtUtc(OffsetDateTime value) {
            this.createdAtUtc = value;
            return this;
        }

        public Builder updatedAtUtc(OffsetDateTime value) {
            this.updatedAtUtc = value;
            return this;
        }

        public DocumentDispatchGroup build() {
            return new DocumentDispatchGroup(id, groupingKey, groupingPolicyCode, groupingPolicyVersion, clientId, establishmentId, clientDisplayName, periodKey, periodLabel, state, revision, documentIds, findings, approvalSnapshot, createdAtUtc, updatedAtUtc);
        }
    }
}
