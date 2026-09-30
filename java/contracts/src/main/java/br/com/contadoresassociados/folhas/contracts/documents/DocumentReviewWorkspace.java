package br.com.contadoresassociados.folhas.contracts.documents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Workspace de revisão. {@code version} permite concorrência otimista no armazenamento
 * (pendência 3.13): quem salvar com versão desatualizada recebe conflito em vez de sobrescrever.
 */
public record DocumentReviewWorkspace(
        String scopeKey,
        List<ReviewDocument> documents,
        List<DocumentDispatchGroup> groups,
        List<ReviewAuditEvent> auditEvents,
        long version) {

    public DocumentReviewWorkspace {
        documents = documents == null ? List.of() : List.copyOf(documents);
        groups = groups == null ? List.of() : List.copyOf(groups);
        auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
    }

    public static DocumentReviewWorkspace empty(String scopeKey) {
        return new DocumentReviewWorkspace(scopeKey, List.of(), List.of(), List.of(), 0);
    }

    @JsonIgnore
    public long eligibleDocumentCount() {
        return documents.stream().filter(d -> d.state() == ReviewDocumentState.READY
                || d.state() == ReviewDocumentState.GROUPED || d.state() == ReviewDocumentState.APPROVED).count();
    }

    @JsonIgnore
    public long blockedDocumentCount() {
        return documents.stream().filter(d -> d.state() == ReviewDocumentState.BLOCKED
                || d.state() == ReviewDocumentState.DUPLICATE).count();
    }

    @JsonIgnore
    public long approvedGroupCount() {
        return groups.stream().filter(DocumentDispatchGroup::isApproved).count();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.scopeKey = scopeKey;
        b.documents = documents;
        b.groups = groups;
        b.auditEvents = auditEvents;
        b.version = version;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private String scopeKey;
        private List<ReviewDocument> documents;
        private List<DocumentDispatchGroup> groups;
        private List<ReviewAuditEvent> auditEvents;
        private long version;

        public Builder scopeKey(String value) {
            this.scopeKey = value;
            return this;
        }

        public Builder documents(List<ReviewDocument> value) {
            this.documents = value;
            return this;
        }

        public Builder groups(List<DocumentDispatchGroup> value) {
            this.groups = value;
            return this;
        }

        public Builder auditEvents(List<ReviewAuditEvent> value) {
            this.auditEvents = value;
            return this;
        }

        public Builder version(long value) {
            this.version = value;
            return this;
        }

        public DocumentReviewWorkspace build() {
            return new DocumentReviewWorkspace(scopeKey, documents, groups, auditEvents, version);
        }
    }
}
