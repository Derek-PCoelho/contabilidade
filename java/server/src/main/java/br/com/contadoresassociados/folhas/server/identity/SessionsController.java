package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.identity.DeviceSessionModel;
import br.com.contadoresassociados.folhas.contracts.identity.SessionInfo;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.db.Sql;
import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import br.com.contadoresassociados.folhas.server.security.Principal;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessões de dispositivo. Mantém a revogação administrativa do .NET
 * ({@code api/security/device-sessions/{id}/revoke}) e acrescenta (pendência 4.16) a consulta da
 * própria sessão, a listagem e a revogação das sessões da própria usuária — o logout do Desktop
 * deixa de depender de cookie e antiforgery.
 */
@RestController
public class SessionsController {

    private final Db db;
    private final Clock clock;
    private final ApiAuthenticator authenticator;
    private final RefreshTokenStore refreshTokens;

    public SessionsController(Db db, Clock clock, ApiAuthenticator authenticator, RefreshTokenStore refreshTokens) {
        this.db = db;
        this.clock = clock;
        this.authenticator = authenticator;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/api/sessions/me")
    @Endpoint(rate = Policy.READ)
    public SessionInfo me(HttpServletRequest http) {
        var user = RequestContext.require(http);
        var organizationName = db.tenantRead(user.organizationId(), c -> {
            try (var st = c.prepareStatement("SELECT \"Name\" FROM organizations WHERE \"Id\" = ?")) {
                st.setObject(1, user.organizationId());
                try (var rs = st.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        return new SessionInfo(user.userId(), user.organizationId(), organizationName, user.email(), user.displayName(),
                user.roles(), user.permissions().stream().sorted().toList(), user.mfa(), user.deviceSessionId(),
                user.expiresAt() == null ? null : Sql.timestamp(user.expiresAt()).toInstant()
                        .atOffset(java.time.ZoneOffset.UTC));
    }

    @GetMapping("/api/sessions")
    @Endpoint(rate = Policy.READ)
    public List<DeviceSessionModel> mine(HttpServletRequest http) {
        var user = RequestContext.require(http);
        return db.tenantRead(user.organizationId(), c -> {
            var list = new ArrayList<DeviceSessionModel>();
            try (var st = c.prepareStatement("SELECT \"Id\", \"DeviceName\", \"CreatedAtUtc\", \"LastSeenAtUtc\", "
                    + "\"RevokedAtUtc\" FROM device_sessions WHERE \"OrganizationId\" = ? AND \"UserId\" = ? "
                    + "ORDER BY \"RevokedAtUtc\" NULLS FIRST, \"LastSeenAtUtc\" DESC LIMIT 100")) {
                st.setObject(1, user.organizationId());
                st.setObject(2, user.userId());
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        var id = Sql.uuid(rs, "Id");
                        list.add(new DeviceSessionModel(id, rs.getString("DeviceName"), Sql.utc(rs, "CreatedAtUtc"),
                                Sql.utc(rs, "LastSeenAtUtc"), Sql.utc(rs, "RevokedAtUtc"),
                                id.equals(user.deviceSessionId())));
                    }
                }
            }
            return list;
        });
    }

    /** Logout do Desktop: revoga a sessão atual (e os refresh tokens dela). */
    @PostMapping("/api/sessions/me/revoke")
    @Endpoint(rate = Policy.SENSITIVE)
    public ResponseEntity<Void> revokeCurrent(HttpServletRequest http) {
        var user = RequestContext.require(http);
        revoke(user, user.deviceSessionId(), true, "logout");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/sessions/{deviceSessionId}/revoke")
    @Endpoint(rate = Policy.SENSITIVE)
    public ResponseEntity<Void> revokeMine(HttpServletRequest http, @PathVariable UUID deviceSessionId) {
        var user = RequestContext.require(http);
        if (!revoke(user, deviceSessionId, true, "revoked")) {
            throw ApiException.notFound("Sessão não encontrada.");
        }
        return ResponseEntity.noContent().build();
    }

    /** "Sair de todos os dispositivos", exceto o atual. */
    @PostMapping("/api/sessions/revoke-others")
    @Endpoint(mfa = true, rate = Policy.SENSITIVE)
    public ResponseEntity<Map<String, Integer>> revokeOthers(HttpServletRequest http) {
        var user = RequestContext.require(http);
        var ids = db.tenantRead(user.organizationId(), c -> {
            var list = new ArrayList<UUID>();
            try (var st = c.prepareStatement("SELECT \"Id\" FROM device_sessions WHERE \"OrganizationId\" = ? AND "
                    + "\"UserId\" = ? AND \"RevokedAtUtc\" IS NULL AND \"Id\" <> ?")) {
                st.setObject(1, user.organizationId());
                st.setObject(2, user.userId());
                st.setObject(3, user.deviceSessionId());
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        list.add(Sql.uuid(rs, "Id"));
                    }
                }
            }
            return list;
        });
        var count = 0;
        for (var id : ids) {
            if (revoke(user, id, true, "revoked")) {
                count++;
            }
        }
        return ResponseEntity.ok(Map.of("revoked", count));
    }

    /** Rota administrativa do .NET, restrita a {@code users.manage} com MFA. */
    @PostMapping("/api/security/device-sessions/{deviceSessionId}/revoke")
    @Endpoint(permissions = "users.manage", mfa = true, rate = Policy.SENSITIVE)
    public ResponseEntity<Void> revokeAny(HttpServletRequest http, @PathVariable UUID deviceSessionId) {
        var user = RequestContext.require(http);
        if (!revoke(user, deviceSessionId, false, "revoked")) {
            throw ApiException.notFound("Sessão não encontrada.");
        }
        return ResponseEntity.noContent().build();
    }

    private boolean revoke(Principal user, UUID sessionId, boolean ownOnly, String action) {
        var found = db.tenant(user.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            var owner = owner(c, user.organizationId(), sessionId);
            if (owner == null || (ownOnly && !owner.equals(user.userId()))) {
                return false;
            }
            try (var st = c.prepareStatement("UPDATE device_sessions SET \"RevokedAtUtc\" = ? WHERE \"Id\" = ? AND "
                    + "\"OrganizationId\" = ? AND \"RevokedAtUtc\" IS NULL")) {
                Sql.instant(st, 1, clock.now());
                st.setObject(2, sessionId);
                st.setObject(3, user.organizationId());
                if (st.executeUpdate() == 1) {
                    AuditLog.append(c, user.organizationId(), user.userId(), user.deviceSessionId(), "device_session",
                            sessionId.toString(), action, "authentication", "warning", Map.of(), clock.now(),
                            UUID.randomUUID());
                }
            }
            refreshTokens.revokeSession(c, sessionId, clock.now());
            return true;
        });
        authenticator.invalidate(sessionId);
        return found;
    }

    private static UUID owner(Connection c, UUID org, UUID sessionId) throws SQLException {
        try (var st = c.prepareStatement("SELECT \"UserId\" FROM device_sessions WHERE \"Id\" = ? AND \"OrganizationId\" = ?")) {
            st.setObject(1, sessionId);
            st.setObject(2, org);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Sql.uuid(rs, "UserId") : null;
            }
        }
    }
}
