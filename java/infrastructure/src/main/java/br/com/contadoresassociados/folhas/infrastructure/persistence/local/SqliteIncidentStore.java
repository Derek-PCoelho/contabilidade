package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.dateTimeOffset;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.guid;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.pascal;

import br.com.contadoresassociados.folhas.application.incidents.IncidentManagement;
import br.com.contadoresassociados.folhas.contracts.json.Json;

public final class SqliteIncidentStore implements IncidentManagement.Store {

    static final String WORKSPACE = "incidents";

    private final LocalDatabase db;

    public SqliteIncidentStore(LocalDatabase db) {
        this.db = db;
    }

    @Override
    public IncidentManagement.Workspace load(String scopeKey) {
        WorkspaceVersions.requireScope(scopeKey);
        return db.read(c -> new IncidentManagement.Workspace(scopeKey,
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"incidents\" WHERE \"ScopeKey\" = ? ORDER BY \"DetectedAtUtc\"",
                        scopeKey, IncidentManagement.Incident.class),
                Rows.payloads(c, "SELECT \"JsonPayload\" FROM \"incident_audit\" WHERE \"ScopeKey\" = ? ORDER BY \"TimestampUtc\"",
                        scopeKey, IncidentManagement.AuditEvent.class),
                WorkspaceVersions.current(c, scopeKey, WORKSPACE)));
    }

    @Override
    public IncidentManagement.Workspace save(IncidentManagement.Workspace ws, long expectedVersion) {
        WorkspaceVersions.requireScope(ws.scopeKey());
        var scope = ws.scopeKey();
        var version = db.transaction(c -> {
            var next = WorkspaceVersions.advance(c, scope, WORKSPACE, expectedVersion);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "incidents" ("ScopeKey", "IncidentId", "DeliveryAttemptId", "Status", "Severity", "Version",
                      "JsonPayload", "DetectedAtUtc", "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ("ScopeKey", "IncidentId") DO UPDATE SET "DeliveryAttemptId" = excluded."DeliveryAttemptId",
                      "Status" = excluded."Status", "Severity" = excluded."Severity", "Version" = excluded."Version",
                      "JsonPayload" = excluded."JsonPayload", "UpdatedAtUtc" = excluded."UpdatedAtUtc\"""")) {
                for (var i : ws.incidents()) {
                    if (!scope.equals(i.scopeKey())) {
                        throw new IllegalStateException("O incidente não pode cruzar o limite do espaço de trabalho.");
                    }
                    ps.setString(1, scope);
                    ps.setString(2, guid(i.id()));
                    ps.setString(3, guid(i.deliveryAttemptId()));
                    ps.setString(4, pascal(i.status()));
                    ps.setString(5, pascal(i.severity()));
                    ps.setLong(6, i.version());
                    ps.setString(7, Json.write(i));
                    ps.setLong(8, dateTimeOffset(i.detectedAtUtc()));
                    ps.setLong(9, dateTimeOffset(i.updatedAtUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            var existing = Rows.ids(c, "SELECT \"EventId\" FROM \"incident_audit\" WHERE \"ScopeKey\" = ?", scope);
            try (var ps = c.prepareStatement("""
                    INSERT INTO "incident_audit" ("ScopeKey", "EventId", "IncidentId", "Action", "JsonPayload", "TimestampUtc")
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                for (var a : ws.auditEvents()) {
                    if (existing.contains(guid(a.id()))) {
                        continue;
                    }
                    ps.setString(1, scope);
                    ps.setString(2, guid(a.id()));
                    ps.setString(3, guid(a.incidentId()));
                    ps.setString(4, a.action());
                    ps.setString(5, Json.write(a));
                    ps.setLong(6, dateTimeOffset(a.timestampUtc()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return next;
        });
        return new IncidentManagement.Workspace(scope, ws.incidents(), ws.auditEvents(), version);
    }
}
