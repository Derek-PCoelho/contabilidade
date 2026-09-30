package br.com.contadoresassociados.folhas.server.sync;

import br.com.contadoresassociados.folhas.server.db.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de mudanças com checkpoint ordenado por organização (pendência 3.1).
 *
 * <p>Na versão .NET o checkpoint vinha de uma sequence global: duas transações podiam obter
 * 10 e 11, a 11 comitar antes e um pull nesse intervalo avançava o cursor para 11, perdendo
 * a 10 para sempre. Aqui toda transação que grava mudanças trava antes a linha da organização
 * em {@code sync_counters} ({@code SELECT … FOR UPDATE}); o checkpoint só é gerado depois da
 * trava e a trava só é liberada no commit, então dentro da organização os checkpoints ficam
 * visíveis na mesma ordem em que foram gerados.
 */
public final class SyncChanges {

    private SyncChanges() {
    }

    /** Deve ser a primeira escrita da transação (evita deadlock com outras travas). */
    public static void lock(Connection c, UUID organizationId) throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO sync_counters (\"OrganizationId\", \"LastCheckpoint\") VALUES (?, 0) "
                + "ON CONFLICT (\"OrganizationId\") DO NOTHING")) {
            st.setObject(1, organizationId);
            st.executeUpdate();
        }
        try (var st = c.prepareStatement("SELECT \"LastCheckpoint\" FROM sync_counters WHERE \"OrganizationId\" = ? "
                + "FOR UPDATE")) {
            st.setObject(1, organizationId);
            st.executeQuery().close();
        }
    }

    public static long record(Connection c, UUID organizationId, UUID entityId, String entityType, Instant now)
            throws SQLException {
        long checkpoint;
        try (var st = c.prepareStatement("INSERT INTO sync_changes (\"OrganizationId\", \"EntityId\", \"EntityType\", "
                + "\"ChangedAtUtc\") VALUES (?, ?, ?, ?) RETURNING \"Checkpoint\"")) {
            st.setObject(1, organizationId);
            st.setObject(2, entityId);
            st.setString(3, entityType);
            Sql.instant(st, 4, now);
            try (var rs = st.executeQuery()) {
                rs.next();
                checkpoint = rs.getLong(1);
            }
        }
        try (var st = c.prepareStatement("UPDATE sync_counters SET \"LastCheckpoint\" = GREATEST(\"LastCheckpoint\", ?), "
                + "\"UpdatedAtUtc\" = ? WHERE \"OrganizationId\" = ?")) {
            st.setLong(1, checkpoint);
            Sql.instant(st, 2, now);
            st.setObject(3, organizationId);
            st.executeUpdate();
        }
        return checkpoint;
    }

    public static long latest(Connection c, UUID organizationId) throws SQLException {
        try (var st = c.prepareStatement("SELECT COALESCE((SELECT \"LastCheckpoint\" FROM sync_counters WHERE "
                + "\"OrganizationId\" = ?), (SELECT max(\"Checkpoint\") FROM sync_changes WHERE \"OrganizationId\" = ?), 0)")) {
            st.setObject(1, organizationId);
            st.setObject(2, organizationId);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
