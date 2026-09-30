package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteCodec.guid;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso à tabela {@code catalog_cache} (mesmo layout do EF: chave {@code (RecordType, RecordId)},
 * {@code UpdatedAtUtc} com o conversor binário de DateTimeOffset). Todas as operações recebem a
 * conexão da transação corrente.
 */
public final class CatalogRecords {

    private CatalogRecords() {
    }

    public static <T> List<T> all(Connection c, String recordType, Class<T> type) throws SQLException {
        var result = new ArrayList<T>();
        try (var ps = c.prepareStatement(
                "SELECT \"JsonPayload\" FROM \"catalog_cache\" WHERE \"RecordType\" = ? ORDER BY \"RecordId\"")) {
            ps.setString(1, recordType);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Json.read(rs.getString(1), type));
                }
            }
        }
        return result;
    }

    /** Os mais recentes primeiro. */
    public static <T> List<T> latest(Connection c, String recordType, int limit, Class<T> type) throws SQLException {
        var result = new ArrayList<T>();
        try (var ps = c.prepareStatement("SELECT \"JsonPayload\" FROM \"catalog_cache\" WHERE \"RecordType\" = ? "
                + "ORDER BY \"UpdatedAtUtc\" DESC LIMIT ?")) {
            ps.setString(1, recordType);
            ps.setInt(2, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Json.read(rs.getString(1), type));
                }
            }
        }
        return result;
    }

    public static <T> Optional<T> find(Connection c, String recordType, UUID id, Class<T> type) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT \"JsonPayload\" FROM \"catalog_cache\" WHERE \"RecordType\" = ? AND \"RecordId\" = ?")) {
            ps.setString(1, recordType);
            ps.setString(2, guid(id));
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Json.read(rs.getString(1), type)) : Optional.empty();
            }
        }
    }

    public static boolean exists(Connection c, String recordType, UUID id) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT 1 FROM \"catalog_cache\" WHERE \"RecordType\" = ? AND \"RecordId\" = ?")) {
            ps.setString(1, recordType);
            ps.setString(2, guid(id));
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void upsert(Connection c, String recordType, UUID id, long version, OffsetDateTime updatedAtUtc,
            Object value) throws SQLException {
        try (var ps = c.prepareStatement("""
                INSERT INTO "catalog_cache" ("RecordType", "RecordId", "JsonPayload", "Version", "UpdatedAtUtc")
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ("RecordType", "RecordId") DO UPDATE SET "JsonPayload" = excluded."JsonPayload",
                  "Version" = excluded."Version", "UpdatedAtUtc" = excluded."UpdatedAtUtc"
                """)) {
            ps.setString(1, recordType);
            ps.setString(2, guid(id));
            ps.setString(3, Json.write(value));
            ps.setLong(4, version);
            ps.setLong(5, SqliteCodec.dateTimeOffset(updatedAtUtc));
            ps.executeUpdate();
        }
    }

    public static boolean delete(Connection c, String recordType, UUID id) throws SQLException {
        try (var ps = c.prepareStatement("DELETE FROM \"catalog_cache\" WHERE \"RecordType\" = ? AND \"RecordId\" = ?")) {
            ps.setString(1, recordType);
            ps.setString(2, guid(id));
            return ps.executeUpdate() > 0;
        }
    }

    /** Lê todos os {@code JsonPayload} de uma tabela operacional (revisões, itens de despacho). */
    public static List<String> rawPayloads(Connection c, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT \"JsonPayload\" FROM \"" + table + "\"")) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    public static boolean anyGroupForClient(Connection c, UUID clientId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM \"document_review_groups\" WHERE \"ClientId\" = ? LIMIT 1")) {
            ps.setString(1, guid(clientId));
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
