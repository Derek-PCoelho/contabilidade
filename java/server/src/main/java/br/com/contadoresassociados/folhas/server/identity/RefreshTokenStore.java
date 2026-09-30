package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.db.Sql;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Códigos de autorização e refresh tokens na tabela {@code oidc_tokens} (esquema do OpenIddict).
 * O valor entregue ao cliente nunca é gravado: {@code ReferenceId} guarda só o SHA-256.
 *
 * <p>Refresh tokens são rotativos; reapresentar um token já trocado revoga toda a família (a
 * sessão de dispositivo inteira), como recomenda o OAuth 2.0 Security BCP.
 */
public final class RefreshTokenStore {

    public static final String TYPE_CODE = "authorization_code";
    public static final String TYPE_REFRESH = "refresh_token";
    public static final String STATUS_VALID = "valid";
    public static final String STATUS_REDEEMED = "redeemed";
    public static final String STATUS_REVOKED = "revoked";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Dados gravados junto do código ou refresh token. */
    public record Grant(UUID userId, UUID organizationId, UUID deviceSessionId, String clientId, List<String> scopes,
            List<String> amr, Instant mfaAt, String securityStamp, String redirectUri, String codeChallenge,
            String nonce) {

        public Grant {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            amr = amr == null ? List.of() : List.copyOf(amr);
        }
    }

    public record Stored(UUID id, String status, Instant expiresAt, Grant grant) {
    }

    public static String newSecret() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issue(Connection c, UUID applicationId, String type, Grant grant, Instant now, Instant expiresAt)
            throws SQLException {
        var secret = newSecret();
        try (var st = c.prepareStatement("INSERT INTO oidc_tokens (\"Id\", \"ApplicationId\", \"ConcurrencyToken\", "
                + "\"CreationDate\", \"ExpirationDate\", \"Payload\", \"Properties\", \"ReferenceId\", \"Status\", \"Subject\", "
                + "\"Type\") VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)")) {
            st.setObject(1, UUID.randomUUID());
            Sql.uuid(st, 2, applicationId);
            st.setString(3, UUID.randomUUID().toString());
            Sql.instant(st, 4, now);
            Sql.instant(st, 5, expiresAt);
            st.setString(6, Json.write(grant));
            st.setString(7, hash(secret));
            st.setString(8, STATUS_VALID);
            st.setString(9, grant.userId().toString());
            st.setString(10, type);
            st.executeUpdate();
        }
        return secret;
    }

    /** Busca e trava o token pelo valor apresentado. */
    public Optional<Stored> find(Connection c, String type, String secret) throws SQLException {
        if (secret == null || secret.isBlank() || secret.length() > 200) {
            return Optional.empty();
        }
        try (var st = c.prepareStatement("SELECT \"Id\", \"Status\", \"ExpirationDate\", \"Properties\" FROM oidc_tokens "
                + "WHERE \"ReferenceId\" = ? AND \"Type\" = ? FOR UPDATE")) {
            st.setString(1, hash(secret));
            st.setString(2, type);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Stored(Sql.uuid(rs, "Id"), rs.getString("Status"),
                        Sql.instant(rs, "ExpirationDate"), Json.read(rs.getString("Properties"), Grant.class)));
            }
        }
    }

    public void markRedeemed(Connection c, UUID id, Instant now) throws SQLException {
        try (var st = c.prepareStatement("UPDATE oidc_tokens SET \"Status\" = ?, \"RedemptionDate\" = ?, "
                + "\"ConcurrencyToken\" = ? WHERE \"Id\" = ?")) {
            st.setString(1, STATUS_REDEEMED);
            Sql.instant(st, 2, now);
            st.setString(3, UUID.randomUUID().toString());
            st.setObject(4, id);
            st.executeUpdate();
        }
    }

    public void revoke(Connection c, UUID id) throws SQLException {
        try (var st = c.prepareStatement("UPDATE oidc_tokens SET \"Status\" = ? WHERE \"Id\" = ?")) {
            st.setString(1, STATUS_REVOKED);
            st.setObject(2, id);
            st.executeUpdate();
        }
    }

    /** Revoga todos os tokens ainda válidos de uma sessão de dispositivo. */
    public int revokeSession(Connection c, UUID deviceSessionId, Instant now) throws SQLException {
        try (var st = c.prepareStatement("UPDATE oidc_tokens SET \"Status\" = ? WHERE \"Status\" = ? AND \"Properties\" "
                + "IS NOT NULL AND \"Properties\"::jsonb ->> 'deviceSessionId' = ?")) {
            st.setString(1, STATUS_REVOKED);
            st.setString(2, STATUS_VALID);
            st.setString(3, deviceSessionId.toString());
            return st.executeUpdate();
        }
    }

    /** Revoga todos os tokens válidos de uma usuária (troca de senha, 2FA, bloqueio). */
    public int revokeUser(Connection c, UUID userId) throws SQLException {
        try (var st = c.prepareStatement("UPDATE oidc_tokens SET \"Status\" = ? WHERE \"Status\" = ? AND \"Subject\" = ?")) {
            st.setString(1, STATUS_REVOKED);
            st.setString(2, STATUS_VALID);
            st.setString(3, userId.toString());
            return st.executeUpdate();
        }
    }

    /** Limpeza de tokens expirados há mais de 7 dias. */
    public int prune(Connection c, Instant now) throws SQLException {
        try (var st = c.prepareStatement("DELETE FROM oidc_tokens WHERE \"ExpirationDate\" < ?")) {
            Sql.instant(st, 1, now.minus(java.time.Duration.ofDays(7)));
            return st.executeUpdate();
        }
    }
}
