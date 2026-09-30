package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.dateTimeOffset;
import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.guid;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.sync.SyncPorts;
import br.com.contadoresassociados.folhas.contracts.sync.ClientSyncItem;
import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.SyncErrorCodes;
import br.com.contadoresassociados.folhas.contracts.sync.UpsertClientCommand;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fila offline e cache de clientes (tabelas {@code offline_sync_operations}, {@code cached_clients}, {@code sync_state}). */
public final class SqliteLocalSyncStore implements SyncPorts.LocalSyncStore {

    private final LocalDatabase db;
    private final Clock clock;

    public SqliteLocalSyncStore(LocalDatabase db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public void initialize() {
        // O esquema já é migrado ao abrir o LocalDatabase.
    }

    @Override
    public void enqueue(UpsertClientCommand cmd) {
        var now = dateTimeOffset(clock.nowUtc());
        db.transaction(c -> {
            try (var ps = c.prepareStatement("""
                    INSERT OR IGNORE INTO "offline_sync_operations" ("OperationId", "ClientId", "DisplayName", "IsActive",
                      "ExpectedVersion", "AttemptCount", "NextAttemptAtUtc", "CreatedAtUtc", "IsConflicted", "LastErrorCode")
                    VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0, NULL)""")) {
                ps.setString(1, guid(cmd.operationId()));
                ps.setString(2, guid(cmd.clientId()));
                ps.setString(3, cmd.displayName());
                ps.setInt(4, cmd.isActive() ? 1 : 0);
                ps.setLong(5, cmd.expectedVersion());
                ps.setLong(6, now);
                ps.setLong(7, now);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public List<SyncPorts.PendingSyncOperation> readyOperations(OffsetDateTime now) {
        // O conversor binário do EF não é ordenável entre offsets distintos: filtramos em memória por instante.
        return db.read(c -> {
            var result = new ArrayList<SyncPorts.PendingSyncOperation>();
            try (var ps = c.prepareStatement("""
                    SELECT "OperationId", "ClientId", "DisplayName", "IsActive", "ExpectedVersion", "AttemptCount",
                      "NextAttemptAtUtc", "CreatedAtUtc" FROM "offline_sync_operations" WHERE "IsConflicted" = 0""");
                    var rs = ps.executeQuery()) {
                var rows = new ArrayList<Object[]>();
                while (rs.next()) {
                    var next = dateTimeOffset(rs.getLong(7));
                    if (!next.isAfter(now)) {
                        var cmd = new UpsertClientCommand(guid(rs.getString(1)), guid(rs.getString(2)), rs.getString(3),
                                rs.getInt(4) != 0, rs.getLong(5));
                        rows.add(new Object[] {dateTimeOffset(rs.getLong(8)),
                                new SyncPorts.PendingSyncOperation(cmd, rs.getInt(6), next, false)});
                    }
                }
                rows.sort((a, b) -> ((OffsetDateTime) a[0]).compareTo((OffsetDateTime) b[0]));
                rows.forEach(r -> result.add((SyncPorts.PendingSyncOperation) r[1]));
            }
            return result;
        });
    }

    @Override
    public void markCompleted(UUID operationId) {
        db.transaction(c -> {
            try (var ps = c.prepareStatement("DELETE FROM \"offline_sync_operations\" WHERE \"OperationId\" = ?")) {
                ps.setString(1, guid(operationId));
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void markConflict(UUID operationId, ClientSyncItem current, String errorCode) {
        db.transaction(c -> {
            try (var ps = c.prepareStatement("""
                    UPDATE "offline_sync_operations" SET "IsConflicted" = 1, "LastErrorCode" = ? WHERE "OperationId" = ?""")) {
                var code = errorCode == null || errorCode.isBlank() ? SyncErrorCodes.CONFLICT : errorCode;
                ps.setString(1, code.length() > 80 ? code.substring(0, 80) : code);
                ps.setString(2, guid(operationId));
                ps.executeUpdate();
            }
            if (current != null) {
                upsert(c, current);
            }
            return null;
        });
    }

    @Override
    public void scheduleRetry(UUID operationId, OffsetDateTime next) {
        db.transaction(c -> {
            try (var ps = c.prepareStatement("""
                    UPDATE "offline_sync_operations" SET "AttemptCount" = "AttemptCount" + 1, "NextAttemptAtUtc" = ?
                    WHERE "OperationId" = ?""")) {
                ps.setLong(1, dateTimeOffset(next.withOffsetSameInstant(java.time.ZoneOffset.UTC)));
                ps.setString(2, guid(operationId));
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public long checkpoint() {
        return db.read(c -> {
            try (var ps = c.prepareStatement("SELECT \"Checkpoint\" FROM \"sync_state\" WHERE \"Id\" = 1");
                    var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public void applyPull(PullSyncResponse response) {
        db.transaction(c -> {
            for (var item : response.clients()) {
                upsert(c, item);
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO "sync_state" ("Id", "Checkpoint") VALUES (1, ?)
                    ON CONFLICT ("Id") DO UPDATE SET "Checkpoint" = MAX("Checkpoint", excluded."Checkpoint")""")) {
                ps.setLong(1, response.checkpoint());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ClientSyncItem> clients() {
        return db.read(c -> {
            var result = new ArrayList<ClientSyncItem>();
            try (var ps = c.prepareStatement("""
                    SELECT "Id", "DisplayName", "IsActive", "Version", "UpdatedAtUtc" FROM "cached_clients"
                    ORDER BY "DisplayName" COLLATE NOCASE""");
                    var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ClientSyncItem(guid(rs.getString(1)), rs.getString(2), rs.getInt(3) != 0,
                            rs.getLong(4), dateTimeOffset(rs.getLong(5))));
                }
            }
            return result;
        });
    }

    private static void upsert(Connection c, ClientSyncItem item) throws SQLException {
        // Nunca regride: só aplica versões maiores ou iguais à armazenada.
        try (var ps = c.prepareStatement("""
                INSERT INTO "cached_clients" ("Id", "DisplayName", "IsActive", "Version", "UpdatedAtUtc") VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ("Id") DO UPDATE SET "DisplayName" = excluded."DisplayName", "IsActive" = excluded."IsActive",
                  "Version" = excluded."Version", "UpdatedAtUtc" = excluded."UpdatedAtUtc"
                WHERE excluded."Version" >= "cached_clients"."Version\"""")) {
            ps.setString(1, guid(item.id()));
            ps.setString(2, item.displayName());
            ps.setInt(3, item.isActive() ? 1 : 0);
            ps.setLong(4, item.version());
            ps.setLong(5, dateTimeOffset(item.updatedAtUtc()));
            ps.executeUpdate();
        }
    }
}
