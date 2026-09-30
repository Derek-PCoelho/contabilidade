package br.com.contadoresassociados.folhas.contracts.dispatch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** Workspace de despacho, com versão para concorrência otimista (pendência 3.13). */
public record DispatchWorkspace(
        String scopeKey,
        List<ProcessingBatch> batches,
        List<DispatchItem> items,
        List<DeliveryAttempt> attempts,
        List<DispatchAuditEvent> auditEvents,
        long version) {

    public DispatchWorkspace {
        batches = batches == null ? List.of() : List.copyOf(batches);
        items = items == null ? List.of() : List.copyOf(items);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
    }

    public static DispatchWorkspace empty(String scopeKey) {
        return new DispatchWorkspace(scopeKey, List.of(), List.of(), List.of(), List.of(), 0);
    }

    @JsonIgnore
    public long recoveryRequiredCount() {
        return attempts.stream().filter(a -> a.state() == DeliveryAttemptState.PENDING
                || a.state() == DeliveryAttemptState.AMBIGUOUS).count();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.scopeKey = scopeKey;
        b.batches = batches;
        b.items = items;
        b.attempts = attempts;
        b.auditEvents = auditEvents;
        b.version = version;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private String scopeKey;
        private List<ProcessingBatch> batches;
        private List<DispatchItem> items;
        private List<DeliveryAttempt> attempts;
        private List<DispatchAuditEvent> auditEvents;
        private long version;

        public Builder scopeKey(String value) {
            this.scopeKey = value;
            return this;
        }

        public Builder batches(List<ProcessingBatch> value) {
            this.batches = value;
            return this;
        }

        public Builder items(List<DispatchItem> value) {
            this.items = value;
            return this;
        }

        public Builder attempts(List<DeliveryAttempt> value) {
            this.attempts = value;
            return this;
        }

        public Builder auditEvents(List<DispatchAuditEvent> value) {
            this.auditEvents = value;
            return this;
        }

        public Builder version(long value) {
            this.version = value;
            return this;
        }

        public DispatchWorkspace build() {
            return new DispatchWorkspace(scopeKey, batches, items, attempts, auditEvents, version);
        }
    }
}
