package br.com.contadoresassociados.folhas.server.sync;

import br.com.contadoresassociados.folhas.contracts.sync.ClientSyncItem;
import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandResult;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandStatus;
import br.com.contadoresassociados.folhas.contracts.sync.SyncErrorCodes;
import br.com.contadoresassociados.folhas.contracts.sync.UpsertClientCommand;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.db.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import br.com.contadoresassociados.folhas.application.common.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta do {@code TenantSyncRepository}: comandos idempotentes por {@code OperationId}, conflito
 * por versão e pull por checkpoint.
 *
 * <p>Correções: 3.1 (checkpoint ordenado por organização), 3.2 (retry de 40001 em {@link Db}),
 * pull paginado com {@code hasMore} (o .NET devolvia todas as mudanças de uma vez).
 */
public final class SyncRepository {

    public static final int PAGE_SIZE = 500;
    private static final UUID EMPTY = new UUID(0, 0);

    private final Db db;
    private final Clock clock;

    public SyncRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public SyncCommandResult apply(UUID organizationId, UUID userId, UUID deviceId, UpsertClientCommand command) {
        if (command == null || command.operationId() == null || EMPTY.equals(command.operationId())
                || command.clientId() == null || EMPTY.equals(command.clientId()) || EMPTY.equals(userId)) {
            return rejected(command == null ? null : command.operationId());
        }
        return db.tenant(organizationId, Db.Isolation.READ_COMMITTED, c -> {
            SyncChanges.lock(c, organizationId);
            var prior = priorOperation(c, organizationId, command.operationId());
            if (prior.isPresent()) {
                var current = find(c, organizationId, prior.get().entityId()).orElse(null);
                return new SyncCommandResult(command.operationId(), SyncCommandStatus.DUPLICATE, current,
                        "Conflict".equals(prior.get().status()) ? SyncErrorCodes.CONFLICT : null);
            }
            var now = clock.now();
            var existing = find(c, organizationId, command.clientId());
            SyncCommandResult result;
            var name = command.displayName() == null ? "" : command.displayName().strip();
            if (name.isEmpty() || name.length() > 200) {
                result = rejected(command.operationId());
            } else if (existing.isEmpty()) {
                if (command.expectedVersion() != 0) {
                    result = conflict(command.operationId(), null);
                } else if (!insert(c, organizationId, userId, command.clientId(), name, now)) {
                    // o id pertence a outra organização (a linha é invisível pelo RLS): recusa sem sobrescrever
                    result = rejected(command.operationId());
                } else {
                    result = applied(command.operationId(), find(c, organizationId, command.clientId()).orElseThrow());
                }
            } else if (existing.get().version() != command.expectedVersion()) {
                result = conflict(command.operationId(), existing.get());
            } else {
                try (var st = c.prepareStatement("UPDATE sync_clients SET \"DisplayName\" = ?, \"IsActive\" = ?, "
                        + "\"ChangedBy\" = ?, \"UpdatedAtUtc\" = ?, \"Version\" = \"Version\" + 1 WHERE \"Id\" = ? AND "
                        + "\"OrganizationId\" = ? AND \"Version\" = ?")) {
                    st.setString(1, name);
                    st.setBoolean(2, command.isActive());
                    st.setObject(3, userId);
                    Sql.instant(st, 4, now);
                    st.setObject(5, command.clientId());
                    st.setObject(6, organizationId);
                    st.setLong(7, command.expectedVersion());
                    if (st.executeUpdate() != 1) {
                        throw Db.retry(new SQLException("versão alterada durante a transação", "40001"));
                    }
                }
                result = applied(command.operationId(), find(c, organizationId, command.clientId()).orElseThrow());
            }
            try (var st = c.prepareStatement("INSERT INTO sync_operations (\"OrganizationId\", \"OperationId\", \"EntityId\", "
                    + "\"Status\", \"ResultVersion\", \"AppliedAtUtc\") VALUES (?, ?, ?, ?, ?, ?)")) {
                st.setObject(1, organizationId);
                st.setObject(2, command.operationId());
                st.setObject(3, command.clientId());
                st.setString(4, Sql.pascal(result.status()));
                st.setLong(5, result.current() == null ? 0 : result.current().version());
                Sql.instant(st, 6, now);
                st.executeUpdate();
            }
            if (result.status() == SyncCommandStatus.APPLIED) {
                SyncChanges.record(c, organizationId, command.clientId(), "client", now);
                var data = new LinkedHashMap<String, Object>();
                data.put("version", result.current().version());
                data.put("active", result.current().isActive());
                AuditLog.append(c, organizationId, userId, deviceId, "client", command.clientId().toString(),
                        result.current().version() == 1 ? "created" : "updated", "registration", "information", data,
                        now, command.operationId());
            }
            return result;
        });
    }

    public PullSyncResponse pull(UUID organizationId, long checkpoint) {
        var from = Math.max(0, checkpoint);
        return db.tenantRead(organizationId, c -> {
            var ids = new LinkedHashMap<UUID, Long>();
            long last = from;
            var hasMore = false;
            try (var st = c.prepareStatement("SELECT \"Checkpoint\", \"EntityId\" FROM sync_changes WHERE "
                    + "\"OrganizationId\" = ? AND \"EntityType\" = 'client' AND \"Checkpoint\" > ? ORDER BY \"Checkpoint\" "
                    + "LIMIT ?")) {
                st.setObject(1, organizationId);
                st.setLong(2, from);
                st.setInt(3, PAGE_SIZE + 1);
                try (var rs = st.executeQuery()) {
                    var count = 0;
                    while (rs.next()) {
                        if (++count > PAGE_SIZE) {
                            hasMore = true;
                            break;
                        }
                        last = rs.getLong(1);
                        ids.put(Sql.uuid(rs, "EntityId"), last);
                    }
                }
            }
            if (ids.isEmpty()) {
                return new PullSyncResponse(from, List.of(), false);
            }
            var clients = new ArrayList<ClientSyncItem>();
            try (var st = c.prepareStatement("SELECT \"Id\", \"DisplayName\", \"IsActive\", \"Version\", \"UpdatedAtUtc\" "
                    + "FROM sync_clients WHERE \"OrganizationId\" = ? AND \"Id\" = ANY (?) ORDER BY \"DisplayName\"")) {
                st.setObject(1, organizationId);
                st.setArray(2, c.createArrayOf("uuid", ids.keySet().toArray()));
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        clients.add(new ClientSyncItem(Sql.uuid(rs, "Id"), rs.getString("DisplayName"),
                                rs.getBoolean("IsActive"), rs.getLong("Version"), Sql.utc(rs, "UpdatedAtUtc")));
                    }
                }
            }
            return new PullSyncResponse(last, clients, hasMore);
        });
    }

    public long latestCheckpoint(UUID organizationId) {
        return db.tenantRead(organizationId, c -> SyncChanges.latest(c, organizationId));
    }

    private record Prior(UUID entityId, String status) {
    }

    private static Optional<Prior> priorOperation(Connection c, UUID organizationId, UUID operationId)
            throws SQLException {
        try (var st = c.prepareStatement("SELECT \"EntityId\", \"Status\" FROM sync_operations WHERE \"OrganizationId\" = ? "
                + "AND \"OperationId\" = ?")) {
            st.setObject(1, organizationId);
            st.setObject(2, operationId);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Optional.of(new Prior(Sql.uuid(rs, "EntityId"), rs.getString("Status")))
                        : Optional.empty();
            }
        }
    }

    private static boolean insert(Connection c, UUID organizationId, UUID userId, UUID id, String name,
            java.time.Instant now) throws SQLException {
        var savepoint = c.setSavepoint();
        try (var st = c.prepareStatement("INSERT INTO sync_clients (\"Id\", \"OrganizationId\", \"DisplayName\", "
                + "\"IsActive\", \"Version\", \"ChangedBy\", \"CreatedAtUtc\", \"UpdatedAtUtc\") "
                + "VALUES (?, ?, ?, true, 1, ?, ?, ?)")) {
            st.setObject(1, id);
            st.setObject(2, organizationId);
            st.setString(3, name);
            st.setObject(4, userId);
            Sql.instant(st, 5, now);
            Sql.instant(st, 6, now);
            st.executeUpdate();
            c.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            if (!Db.isUniqueViolation(e)) {
                throw e;
            }
            c.rollback(savepoint);
            return false;
        }
    }

    private static Optional<ClientSyncItem> find(Connection c, UUID organizationId, UUID id) throws SQLException {
        try (var st = c.prepareStatement("SELECT \"Id\", \"DisplayName\", \"IsActive\", \"Version\", \"UpdatedAtUtc\" "
                + "FROM sync_clients WHERE \"OrganizationId\" = ? AND \"Id\" = ?")) {
            st.setObject(1, organizationId);
            st.setObject(2, id);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Optional.of(new ClientSyncItem(Sql.uuid(rs, "Id"), rs.getString("DisplayName"),
                        rs.getBoolean("IsActive"), rs.getLong("Version"), Sql.utc(rs, "UpdatedAtUtc")))
                        : Optional.empty();
            }
        }
    }

    private static SyncCommandResult applied(UUID operationId, ClientSyncItem client) {
        return new SyncCommandResult(operationId, SyncCommandStatus.APPLIED, client, null);
    }

    private static SyncCommandResult conflict(UUID operationId, ClientSyncItem client) {
        return new SyncCommandResult(operationId, SyncCommandStatus.CONFLICT, client, SyncErrorCodes.CONFLICT);
    }

    private static SyncCommandResult rejected(UUID operationId) {
        return new SyncCommandResult(operationId, SyncCommandStatus.REJECTED, null, SyncErrorCodes.INVALID_COMMAND);
    }
}
