package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        UUID batchId,
        UUID dispatchItemId,
        UUID groupId,
        int attemptNumber,
        DispatchOperationMode mode,
        DeliveryAttemptState state,
        String providerKey,
        String idempotencyKey,
        String dispatchFingerprint,
        String providerMessageId,
        String providerDraftId,
        String errorCode,
        String redactedError,
        OffsetDateTime startedAtUtc,
        OffsetDateTime completedAtUtc) {

    public Builder toBuilder() {
        var b = new Builder();
        b.id = id;
        b.batchId = batchId;
        b.dispatchItemId = dispatchItemId;
        b.groupId = groupId;
        b.attemptNumber = attemptNumber;
        b.mode = mode;
        b.state = state;
        b.providerKey = providerKey;
        b.idempotencyKey = idempotencyKey;
        b.dispatchFingerprint = dispatchFingerprint;
        b.providerMessageId = providerMessageId;
        b.providerDraftId = providerDraftId;
        b.errorCode = errorCode;
        b.redactedError = redactedError;
        b.startedAtUtc = startedAtUtc;
        b.completedAtUtc = completedAtUtc;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private UUID id;
        private UUID batchId;
        private UUID dispatchItemId;
        private UUID groupId;
        private int attemptNumber;
        private DispatchOperationMode mode;
        private DeliveryAttemptState state;
        private String providerKey;
        private String idempotencyKey;
        private String dispatchFingerprint;
        private String providerMessageId;
        private String providerDraftId;
        private String errorCode;
        private String redactedError;
        private OffsetDateTime startedAtUtc;
        private OffsetDateTime completedAtUtc;

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder batchId(UUID value) {
            this.batchId = value;
            return this;
        }

        public Builder dispatchItemId(UUID value) {
            this.dispatchItemId = value;
            return this;
        }

        public Builder groupId(UUID value) {
            this.groupId = value;
            return this;
        }

        public Builder attemptNumber(int value) {
            this.attemptNumber = value;
            return this;
        }

        public Builder mode(DispatchOperationMode value) {
            this.mode = value;
            return this;
        }

        public Builder state(DeliveryAttemptState value) {
            this.state = value;
            return this;
        }

        public Builder providerKey(String value) {
            this.providerKey = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder dispatchFingerprint(String value) {
            this.dispatchFingerprint = value;
            return this;
        }

        public Builder providerMessageId(String value) {
            this.providerMessageId = value;
            return this;
        }

        public Builder providerDraftId(String value) {
            this.providerDraftId = value;
            return this;
        }

        public Builder errorCode(String value) {
            this.errorCode = value;
            return this;
        }

        public Builder redactedError(String value) {
            this.redactedError = value;
            return this;
        }

        public Builder startedAtUtc(OffsetDateTime value) {
            this.startedAtUtc = value;
            return this;
        }

        public Builder completedAtUtc(OffsetDateTime value) {
            this.completedAtUtc = value;
            return this;
        }

        public DeliveryAttempt build() {
            return new DeliveryAttempt(id, batchId, dispatchItemId, groupId, attemptNumber, mode, state, providerKey, idempotencyKey, dispatchFingerprint, providerMessageId, providerDraftId, errorCode, redactedError, startedAtUtc, completedAtUtc);
        }
    }
}
