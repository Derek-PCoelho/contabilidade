package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.dateTimeOffset;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.guid;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.pascal;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowStore;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingBatch;
import br.com.contadoresassociados.folhas.contracts.json.Json;

/**
 * Store do despacho. As tentativas nunca são apagadas (o .NET apagava e regravava todas; aqui só
 * inserimos novas e atualizamos existentes, preservando o índice único de idempotência).
 */
public final class SqliteDispatchWorkflowStore implements DispatchWorkflowStore {

    static final String WORKSPACE = "dispatch";

    private final LocalDatabase db;

    public SqliteDispatchWorkflowStore(LocalDatabase db) {
        this.db = db;
    }

    @Override
    public DispatchWorkspace load(String scopeKey) {
        WorkspaceVersions.requireScope(scopeKey);
        return db.read(c -> new DispatchWorkspace(scopeKey,
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"processing_batches\" WHERE \"ScopeKey\" = ? ORDER BY \"UpdatedAtUtc\"",
                        scopeKey, ProcessingBatch.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"dispatch_items\" WHERE \"ScopeKey\" = ? ORDER BY \"UpdatedAtUtc\"",
                        scopeKey, DispatchItem.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"delivery_attempts\" WHERE \"ScopeKey\" = ? ORDER BY \"StartedAtUtc\"",
                        scopeKey, DeliveryAttempt.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"dispatch_audit\" WHERE \"ScopeKey\" = ? ORDER BY \"TimestampUtc\"",
                        scopeKey, DispatchAuditEvent.class),
                WorkspaceVersions.current(c, scopeKey, WORKSPACE)));
    }

    @Override
    public DispatchWorkspace save(DispatchWorkspace ws, long expectedVersion) {
        WorkspaceVersions.requireScope(ws.scopeKey());
        var scope = ws.scopeKey();
        var version = db.transaction(c -> {
            var next = WorkspaceVersions.advance(c, scope, WORKSPACE, expectedVersion);
            Rows.delete(c, "processing_batches", scope);
            Rows.delete(c, "dispatch_items", scope);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "processing_batches" ("ScopeKey", "BatchId", "State", "OperationMode", "JsonPayload",
                      "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?, ?)""")) {
                for (var b : ws.batches()) {
                    ps.setString(1, scope);
                    ps.setString(2, guid(b.id()));
                    ps.setString(3, pascal(b.state()));
                    ps.setString(4, pascal(b.operationMode()));
                    ps.setString(5, Json.write(b));
                    ps.setLong(6, dateTimeOffset(b.updatedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO "dispatch_items" ("ScopeKey", "DispatchItemId", "BatchId", "GroupId", "State",
                      "DispatchFingerprint", "JsonPayload", "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (var i : ws.items()) {
                    ps.setString(1, scope);
                    ps.setString(2, guid(i.id()));
                    ps.setString(3, guid(i.batchId()));
                    ps.setString(4, guid(i.groupId()));
                    ps.setString(5, pascal(i.state()));
                    ps.setString(6, i.message() == null ? "" : i.message().dispatchFingerprint());
                    ps.setString(7, Json.write(i));
                    ps.setLong(8, dateTimeOffset(i.updatedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO "delivery_attempts" ("ScopeKey", "AttemptId", "DispatchItemId", "State", "IdempotencyKey",
                      "JsonPayload", "StartedAtUtc") VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ("ScopeKey", "AttemptId") DO UPDATE SET "State" = excluded."State",
                      "JsonPayload" = excluded."JsonPayload\"""")) {
                for (var a : ws.attempts()) {
                    ps.setString(1, scope);
                    ps.setString(2, guid(a.id()));
                    ps.setString(3, guid(a.dispatchItemId()));
                    ps.setString(4, pascal(a.state()));
                    ps.setString(5, a.idempotencyKey());
                    ps.setString(6, Json.write(a));
                    ps.setLong(7, dateTimeOffset(a.startedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            var existing = Rows.ids(c, "SELECT \"EventId\" FROM \"dispatch_audit\" WHERE \"ScopeKey\" = ?", scope);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "dispatch_audit" ("ScopeKey", "EventId", "Action", "DispatchItemId", "JsonPayload",
                      "TimestampUtc") VALUES (?, ?, ?, ?, ?, ?)""")) {
                for (var e : ws.auditEvents()) {
                    if (existing.contains(guid(e.id()))) {
                        continue;
                    }
                    ps.setString(1, scope);
                    ps.setString(2, guid(e.id()));
                    ps.setString(3, e.action());
                    ps.setString(4, guid(e.dispatchItemId()));
                    ps.setString(5, Json.write(e));
                    ps.setLong(6, dateTimeOffset(e.timestampUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return next;
        });
        return ws.toBuilder().version(version).build();
    }
}
