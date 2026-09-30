package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import java.sql.Connection;
import java.sql.SQLException;

/** Versão otimista por (escopo, workspace) — pendência 3.13. */
final class WorkspaceVersions {

    private WorkspaceVersions() {
    }

    static long current(Connection c, String scopeKey, String workspace) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT \"Version\" FROM \"workspace_versions\" WHERE \"ScopeKey\" = ? AND \"Workspace\" = ?")) {
            ps.setString(1, scopeKey);
            ps.setString(2, workspace);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Verifica a versão esperada e grava a próxima; deve ser chamado dentro da transação do save. */
    static long advance(Connection c, String scopeKey, String workspace, long expected) throws SQLException {
        var current = current(c, scopeKey, workspace);
        if (current != expected) {
            throw new WorkspaceConflictException(scopeKey, expected, current);
        }
        var next = current + 1;
        try (var ps = c.prepareStatement("""
                INSERT INTO "workspace_versions" ("ScopeKey", "Workspace", "Version") VALUES (?, ?, ?)
                ON CONFLICT ("ScopeKey", "Workspace") DO UPDATE SET "Version" = excluded."Version\"""")) {
            ps.setString(1, scopeKey);
            ps.setString(2, workspace);
            ps.setLong(3, next);
            ps.executeUpdate();
        }
        return next;
    }

    static void requireScope(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank() || scopeKey.length() > 64) {
            throw new IllegalArgumentException("Escopo do espaço de trabalho inválido.");
        }
    }
}
