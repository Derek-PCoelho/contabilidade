package br.com.contadoresassociados.folhas.contracts.dispatch;

import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DispatchItem(
        UUID id,
        UUID batchId,
        UUID groupId,
        UUID clientId,
        String clientDisplayName,
        UUID establishmentId,
        String periodLabel,
        DispatchOperationMode mode,
        FakeDeliveryScenario scenario,
        String testDestination,
        DispatchItemState state,
        long revision,
        RenderedMessageSnapshot message,
        DispatchApprovalSnapshot approval,
        List<DispatchBlock> blocks,
        OffsetDateTime createdAtUtc,
        OffsetDateTime updatedAtUtc) {

    public DispatchItem {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isApproved() {
        return approval != null && state == DispatchItemState.APPROVED;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean preventsApproval() {
        return blocks.stream().anyMatch(b -> b.severity() == ValidationSeverity.ERROR
                || b.severity() == ValidationSeverity.BLOCKER);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.batchId = batchId;
        b.groupId = groupId;
        b.clientId = clientId;
        b.clientDisplayName = clientDisplayName;
        b.establishmentId = establishmentId;
        b.periodLabel = periodLabel;
        b.mode = mode;
        b.scenario = scenario;
        b.testDestination = testDestination;
        b.state = state;
        b.revision = revision;
        b.message = message;
        b.approval = approval;
        b.blocks = blocks;
        b.createdAtUtc = createdAtUtc;
        b.updatedAtUtc = updatedAtUtc;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private UUID batchId;
        private UUID groupId;
        private UUID clientId;
        private String clientDisplayName;
        private UUID establishmentId;
        private String periodLabel;
        private DispatchOperationMode mode;
        private FakeDeliveryScenario scenario;
        private String testDestination;
        private DispatchItemState state;
        private long revision;
        private RenderedMessageSnapshot message;
        private DispatchApprovalSnapshot approval;
        private List<DispatchBlock> blocks;
        private OffsetDateTime createdAtUtc;
        private OffsetDateTime updatedAtUtc;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder batchId(UUID value) {
            this.batchId = value;
            return this;
        }

        public Builder groupId(UUID value) {
            this.groupId = value;
            return this;
        }

        public Builder clientId(UUID value) {
            this.clientId = value;
            return this;
        }

        public Builder clientDisplayName(String value) {
            this.clientDisplayName = value;
            return this;
        }

        public Builder establishmentId(UUID value) {
            this.establishmentId = value;
            return this;
        }

        public Builder periodLabel(String value) {
            this.periodLabel = value;
            return this;
        }

        public Builder mode(DispatchOperationMode value) {
            this.mode = value;
            return this;
        }

        public Builder scenario(FakeDeliveryScenario value) {
            this.scenario = value;
            return this;
        }

        public Builder testDestination(String value) {
            this.testDestination = value;
            return this;
        }

        public Builder state(DispatchItemState value) {
            this.state = value;
            return this;
        }

        public Builder revision(long value) {
            this.revision = value;
            return this;
        }

        public Builder message(RenderedMessageSnapshot value) {
            this.message = value;
            return this;
        }

        public Builder approval(DispatchApprovalSnapshot value) {
            this.approval = value;
            return this;
        }

        public Builder blocks(List<DispatchBlock> value) {
            this.blocks = value;
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

        public DispatchItem build() {
            return new DispatchItem(id, batchId, groupId, clientId, clientDisplayName, establishmentId, periodLabel, mode, scenario, testDestination, state, revision, message, approval, blocks, createdAtUtc, updatedAtUtc);
        }
    }
}
