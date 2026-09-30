package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Utilidades JDBC para linhas com {@code JsonPayload}. */
final class Rows {

    private Rows() {
    }

    static <T> List<T> payloads(Connection c, String sql, String scopeKey, Class<T> type) throws SQLException {
        var result = new ArrayList<T>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, scopeKey);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Json.read(rs.getString(1), type));
                }
            }
        }
        return result;
    }

    static Set<String> ids(Connection c, String sql, String scopeKey) throws SQLException {
        var result = new HashSet<String>();
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, scopeKey);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1).toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return result;
    }

    static void delete(Connection c, String table, String scopeKey) throws SQLException {
        try (var ps = c.prepareStatement("DELETE FROM \"" + table + "\" WHERE \"ScopeKey\" = ?")) {
            ps.setString(1, scopeKey);
            ps.executeUpdate();
        }
    }
}
