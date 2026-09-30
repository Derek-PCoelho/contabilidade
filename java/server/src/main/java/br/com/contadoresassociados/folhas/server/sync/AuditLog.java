package br.com.contadoresassociados.folhas.server.sync;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.db.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Inclusão na trilha de auditoria (append-only também por trigger, migração V2). */
public final class AuditLog {

    private AuditLog() {
    }

    public static UUID append(Connection c, UUID organizationId, UUID userId, UUID deviceId, String entityType,
            String entityId, String action, String category, String severity, Map<String, ?> redactedData,
            Instant now, UUID correlationId) throws SQLException {
        var id = UUID.randomUUID();
        try (var st = c.prepareStatement("INSERT INTO audit_events (\"Id\", \"OrganizationId\", \"UserId\", \"DeviceId\", "
                + "\"EntityType\", \"EntityId\", \"Action\", \"Category\", \"Severity\", \"RedactedDataJson\", "
                + "\"TimestampUtc\", \"CorrelationId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")) {
            st.setObject(1, id);
            st.setObject(2, organizationId);
            st.setObject(3, userId);
            Sql.uuid(st, 4, deviceId);
            st.setString(5, entityType);
            st.setString(6, entityId);
            st.setString(7, action);
            st.setString(8, category);
            st.setString(9, severity);
            st.setString(10, Json.write(redactedData));
            Sql.instant(st, 11, now);
            st.setObject(12, correlationId);
            st.executeUpdate();
        }
        return id;
    }
}
