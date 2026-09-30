package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProcessingBatch(
        UUID id,
        String scopeKey,
        ProcessingSelectionMode selectionMode,
        DispatchOperationMode operationMode,
        ProcessingBatchState state,
        List<UUID> groupIds,
        List<UUID> dispatchItemIds,
        String createdBy,
        OffsetDateTime createdAtUtc,
        OffsetDateTime updatedAtUtc) {

    public ProcessingBatch {
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        dispatchItemIds = dispatchItemIds == null ? List.of() : List.copyOf(dispatchItemIds);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.scopeKey = scopeKey;
        b.selectionMode = selectionMode;
        b.operationMode = operationMode;
        b.state = state;
        b.groupIds = groupIds;
        b.dispatchItemIds = dispatchItemIds;
        b.createdBy = createdBy;
        b.createdAtUtc = createdAtUtc;
        b.updatedAtUtc = updatedAtUtc;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private String scopeKey;
        private ProcessingSelectionMode selectionMode;
        private DispatchOperationMode operationMode;
        private ProcessingBatchState state;
        private List<UUID> groupIds;
        private List<UUID> dispatchItemIds;
        private String createdBy;
        private OffsetDateTime createdAtUtc;
        private OffsetDateTime updatedAtUtc;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder scopeKey(String value) {
            this.scopeKey = value;
            return this;
        }

        public Builder selectionMode(ProcessingSelectionMode value) {
            this.selectionMode = value;
            return this;
        }

        public Builder operationMode(DispatchOperationMode value) {
            this.operationMode = value;
            return this;
        }

        public Builder state(ProcessingBatchState value) {
            this.state = value;
            return this;
        }

        public Builder groupIds(List<UUID> value) {
            this.groupIds = value;
            return this;
        }

        public Builder dispatchItemIds(List<UUID> value) {
            this.dispatchItemIds = value;
            return this;
        }

        public Builder createdBy(String value) {
            this.createdBy = value;
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

        public ProcessingBatch build() {
            return new ProcessingBatch(id, scopeKey, selectionMode, operationMode, state, groupIds, dispatchItemIds, createdBy, createdAtUtc, updatedAtUtc);
        }
    }
}
