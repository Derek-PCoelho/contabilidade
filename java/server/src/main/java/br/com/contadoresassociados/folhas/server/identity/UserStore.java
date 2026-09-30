package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.server.db.Sql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso às tabelas do ASP.NET Identity ({@code users}, {@code roles}, {@code user_roles},
 * {@code user_tokens}) com as mesmas convenções: e-mail/usuário normalizados em maiúsculas,
 * {@code SecurityStamp} trocado a cada mudança sensível e bloqueio após 5 falhas por 15 minutos.
 *
 * <p>Pendência 3.9: {@code Version} passa a ser token de concorrência (toda alteração exige a
 * versão lida e a incrementa).
 */
public final class UserStore {

    public static final String IDENTITY_PROVIDER = "[AspNetUserStore]";
    public static final String AUTHENTICATOR_KEY = "AuthenticatorKey";
    public static final String RECOVERY_CODES = "RecoveryCodes";
    public static final String LAST_TOTP_STEP = "FolhasLastTotpStep";
    public static final int MAX_FAILED_ACCESS = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    private static final String SELECT = "SELECT \"Id\", \"OrganizationId\", \"DisplayName\", \"IsActive\", \"Version\", "
            + "\"UserName\", \"Email\", \"EmailConfirmed\", \"PasswordHash\", \"SecurityStamp\", \"TwoFactorEnabled\", "
            + "\"LockoutEnd\", \"LockoutEnabled\", \"AccessFailedCount\" FROM users ";

    public static String normalize(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    public Optional<UserRecord> findByEmail(Connection c, String email) throws SQLException {
        try (var st = c.prepareStatement(SELECT + "WHERE \"NormalizedEmail\" = ? ORDER BY \"Id\" LIMIT 2")) {
            st.setString(1, normalize(email));
            try (var rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                var user = read(c, rs);
                // Identity exige e-mail único; se houver duplicata o login é recusado.
                return rs.next() ? Optional.empty() : Optional.of(user);
            }
        }
    }

    public Optional<UserRecord> findById(Connection c, UUID id) throws SQLException {
        try (var st = c.prepareStatement(SELECT + "WHERE \"Id\" = ?")) {
            st.setObject(1, id);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Optional.of(read(c, rs)) : Optional.empty();
            }
        }
    }

    private UserRecord read(Connection c, ResultSet rs) throws SQLException {
        var id = Sql.uuid(rs, "Id");
        return new UserRecord(id, Sql.uuid(rs, "OrganizationId"), rs.getString("DisplayName"),
                rs.getBoolean("IsActive"), rs.getLong("Version"), rs.getString("UserName"), rs.getString("Email"),
                rs.getBoolean("EmailConfirmed"), rs.getString("PasswordHash"), rs.getString("SecurityStamp"),
                rs.getBoolean("TwoFactorEnabled"), Sql.instant(rs, "LockoutEnd"), rs.getBoolean("LockoutEnabled"),
                rs.getInt("AccessFailedCount"), roles(c, id));
    }

    public List<String> roles(Connection c, UUID userId) throws SQLException {
        try (var st = c.prepareStatement("SELECT r.\"Name\" FROM user_roles ur JOIN roles r ON r.\"Id\" = ur.\"RoleId\" "
                + "WHERE ur.\"UserId\" = ? ORDER BY r.\"Name\"")) {
            st.setObject(1, userId);
            try (var rs = st.executeQuery()) {
                var list = new ArrayList<String>();
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
                return list;
            }
        }
    }

    /** Registra falha de senha/código; bloqueia após 5 falhas seguidas (Identity padrão). */
    public void accessFailed(Connection c, UUID userId, Instant now) throws SQLException {
        try (var st = c.prepareStatement("UPDATE users SET \"AccessFailedCount\" = CASE WHEN \"AccessFailedCount\" + 1 >= ? "
                + "THEN 0 ELSE \"AccessFailedCount\" + 1 END, \"LockoutEnd\" = CASE WHEN \"LockoutEnabled\" AND "
                + "\"AccessFailedCount\" + 1 >= ? THEN ? ELSE \"LockoutEnd\" END, \"ConcurrencyStamp\" = ?, "
                + "\"Version\" = \"Version\" + 1 WHERE \"Id\" = ?")) {
            st.setInt(1, MAX_FAILED_ACCESS);
            st.setInt(2, MAX_FAILED_ACCESS);
            Sql.instant(st, 3, now.plus(LOCKOUT));
            st.setString(4, UUID.randomUUID().toString());
            st.setObject(5, userId);
            st.executeUpdate();
        }
    }

    public void signedIn(Connection c, UUID userId, Instant now, String newPasswordHash) throws SQLException {
        var sql = "UPDATE users SET \"AccessFailedCount\" = 0, \"LockoutEnd\" = NULL, \"LastAccessAtUtc\" = ?, "
                + "\"ConcurrencyStamp\" = ?, \"Version\" = \"Version\" + 1"
                + (newPasswordHash == null ? "" : ", \"PasswordHash\" = ?") + " WHERE \"Id\" = ?";
        try (var st = c.prepareStatement(sql)) {
            Sql.instant(st, 1, now);
            st.setString(2, UUID.randomUUID().toString());
            var i = 3;
            if (newPasswordHash != null) {
                st.setString(i++, newPasswordHash);
            }
            st.setObject(i, userId);
            st.executeUpdate();
        }
    }

    /** Alteração sensível (senha, 2FA, bloqueio): troca o stamp e derruba sessões/refresh (4.3). */
    public boolean update(Connection c, UserRecord user, String passwordHash, boolean twoFactorEnabled)
            throws SQLException {
        try (var st = c.prepareStatement("UPDATE users SET \"PasswordHash\" = ?, \"TwoFactorEnabled\" = ?, "
                + "\"SecurityStamp\" = ?, \"ConcurrencyStamp\" = ?, \"Version\" = \"Version\" + 1 "
                + "WHERE \"Id\" = ? AND \"Version\" = ?")) {
            st.setString(1, passwordHash);
            st.setBoolean(2, twoFactorEnabled);
            st.setString(3, newSecurityStamp());
            st.setString(4, UUID.randomUUID().toString());
            st.setObject(5, user.id());
            st.setLong(6, user.version());
            return st.executeUpdate() == 1;
        }
    }

    public Optional<String> token(Connection c, UUID userId, String name) throws SQLException {
        try (var st = c.prepareStatement("SELECT \"Value\" FROM user_tokens WHERE \"UserId\" = ? AND \"LoginProvider\" = ? "
                + "AND \"Name\" = ?")) {
            st.setObject(1, userId);
            st.setString(2, IDENTITY_PROVIDER);
            st.setString(3, name);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public void setToken(Connection c, UUID userId, String name, String value) throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO user_tokens (\"UserId\", \"LoginProvider\", \"Name\", \"Value\") "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (\"UserId\", \"LoginProvider\", \"Name\") DO UPDATE SET \"Value\" = "
                + "EXCLUDED.\"Value\"")) {
            st.setObject(1, userId);
            st.setString(2, IDENTITY_PROVIDER);
            st.setString(3, name);
            st.setString(4, value);
            st.executeUpdate();
        }
    }

    public UUID create(Connection c, UUID organizationId, String email, String displayName, String passwordHash,
            List<String> roleNames) throws SQLException {
        var id = UUID.randomUUID();
        try (var st = c.prepareStatement("INSERT INTO users (\"Id\", \"OrganizationId\", \"DisplayName\", \"IsActive\", "
                + "\"Version\", \"UserName\", \"NormalizedUserName\", \"Email\", \"NormalizedEmail\", \"EmailConfirmed\", "
                + "\"PasswordHash\", \"SecurityStamp\", \"ConcurrencyStamp\", \"PhoneNumberConfirmed\", "
                + "\"TwoFactorEnabled\", \"LockoutEnabled\", \"AccessFailedCount\") VALUES (?, ?, ?, true, 1, ?, ?, ?, ?, "
                + "true, ?, ?, ?, false, false, true, 0)")) {
            st.setObject(1, id);
            st.setObject(2, organizationId);
            st.setString(3, displayName);
            st.setString(4, email.strip());
            st.setString(5, normalize(email));
            st.setString(6, email.strip());
            st.setString(7, normalize(email));
            st.setString(8, passwordHash);
            st.setString(9, newSecurityStamp());
            st.setString(10, UUID.randomUUID().toString());
            st.executeUpdate();
        }
        for (var role : roleNames) {
            try (var st = c.prepareStatement("INSERT INTO user_roles (\"UserId\", \"RoleId\") SELECT ?, \"Id\" FROM roles "
                    + "WHERE \"NormalizedName\" = ?")) {
                st.setObject(1, id);
                st.setString(2, normalize(role));
                if (st.executeUpdate() != 1) {
                    throw new SQLException("Papel inexistente: " + role, "23503");
                }
            }
        }
        return id;
    }

    static String newSecurityStamp() {
        var bytes = new byte[20];
        new java.security.SecureRandom().nextBytes(bytes);
        return br.com.contadoresassociados.folhas.server.security.Totp.base32(bytes);
    }
}
