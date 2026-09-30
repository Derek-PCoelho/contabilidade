package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.dateTimeOffset;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.guid;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.pascal;

import br.com.contadoresassociados.folhas.application.documents.DocumentReviewStore;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewAuditEvent;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Store da revisão em {@code document_reviews}, {@code document_review_groups} e {@code document_review_audit}. */
public final class SqliteDocumentReviewStore implements DocumentReviewStore {

    static final String WORKSPACE = "document_review";
    private static final OffsetDateTime EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final LocalDatabase db;

    public SqliteDocumentReviewStore(LocalDatabase db) {
        this.db = db;
    }

    @Override
    public DocumentReviewWorkspace load(String scopeKey) {
        WorkspaceVersions.requireScope(scopeKey);
        return db.read(c -> new DocumentReviewWorkspace(scopeKey,
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"document_reviews\" WHERE \"ScopeKey\" = ? ORDER BY \"UpdatedAtUtc\"",
                        scopeKey, ReviewDocument.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"document_review_groups\" WHERE \"ScopeKey\" = ? ORDER BY \"UpdatedAtUtc\"",
                        scopeKey, DocumentDispatchGroup.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"document_review_audit\" WHERE \"ScopeKey\" = ? ORDER BY \"TimestampUtc\"",
                        scopeKey, ReviewAuditEvent.class),
                WorkspaceVersions.current(c, scopeKey, WORKSPACE)));
    }

    @Override
    public DocumentReviewWorkspace save(DocumentReviewWorkspace ws, long expectedVersion) {
        WorkspaceVersions.requireScope(ws.scopeKey());
        var scope = ws.scopeKey();
        var version = db.transaction(c -> {
            var next = WorkspaceVersions.advance(c, scope, WORKSPACE, expectedVersion);
            Rows.delete(c, "document_reviews", scope);
            Rows.delete(c, "document_review_groups", scope);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "document_reviews" ("ScopeKey", "DocumentId", "Sha256", "SemanticDuplicateKey", "State",
                      "JsonPayload", "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                for (var d : ws.documents()) {
                    ps.setString(1, scope);
                    ps.setString(2, guid(d.id()));
                    ps.setString(3, d.sha256());
                    ps.setString(4, d.semanticDuplicateKey() == null ? "" : d.semanticDuplicateKey());
                    ps.setString(5, pascal(d.state()));
                    ps.setString(6, Json.write(d));
                    ps.setLong(7, dateTimeOffset(d.validatedAtUtc() == null ? EPOCH : d.validatedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO "document_review_groups" ("ScopeKey", "GroupId", "ClientId", "PeriodKey", "State",
                      "JsonPayload", "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                for (var g : ws.groups()) {
                    ps.setString(1, scope);
                    ps.setString(2, guid(g.id()));
                    ps.setString(3, guid(g.clientId() == null ? new java.util.UUID(0, 0) : g.clientId()));
                    ps.setString(4, g.periodKey() == null ? "" : g.periodKey());
                    ps.setString(5, pascal(g.state()));
                    ps.setString(6, Json.write(g));
                    ps.setLong(7, dateTimeOffset(g.updatedAtUtc() == null ? EPOCH : g.updatedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            var existing = Rows.ids(c, "SELECT \"EventId\" FROM \"document_review_audit\" WHERE \"ScopeKey\" = ?", scope);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "document_review_audit" ("ScopeKey", "EventId", "Action", "DocumentId", "GroupId",
                      "JsonPayload", "TimestampUtc") VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                for (var a : ws.auditEvents()) {
                    if (existing.contains(guid(a.id()))) {
                        continue;
                    }
                    ps.setString(1, scope);
                    ps.setString(2, guid(a.id()));
                    ps.setString(3, a.action());
                    ps.setString(4, guid(a.documentId()));
                    ps.setString(5, guid(a.groupId()));
                    ps.setString(6, Json.write(a));
                    ps.setLong(7, dateTimeOffset(a.timestampUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return next;
        });
        return ws.toBuilder().version(version).build();
    }
}
