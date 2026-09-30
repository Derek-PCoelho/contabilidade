package br.com.contadoresassociados.folhas.server.security;

import br.com.contadoresassociados.folhas.server.config.ServerSettings;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import br.com.contadoresassociados.folhas.application.common.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autentica requisições de API.
 *
 * <ul>
 * <li>Bearer: JWT assinado emitido por {@code /connect/token}, validado localmente.</li>
 * <li>Desenvolvimento: cabeçalhos {@code X-Development-*}, só em Development/Testing com o
 * interruptor ligado e, fora de Testing, apenas via loopback (igual ao .NET).</li>
 * </ul>
 *
 * <p>Além da assinatura, confere a cada requisição (com cache curto) que a sessão de dispositivo
 * não foi revogada e que a usuária continua ativa e sem bloqueio (4.3/4.14): revogar uma sessão
 * ou bloquear a conta derruba o acesso imediatamente, não só no próximo refresh.
 */
public final class ApiAuthenticator {

    public static final String USER_HEADER = "X-Development-User";
    public static final String ORGANIZATION_HEADER = "X-Development-Organization";
    public static final String DEVICE_HEADER = "X-Development-Device";
    public static final String PERMISSIONS_HEADER = "X-Development-Permissions";
    public static final String MFA_HEADER = "X-Development-Mfa";
    public static final String ROLES_HEADER = "X-Development-Roles";
    private static final Duration SESSION_CACHE = Duration.ofSeconds(15);

    private record CacheEntry(boolean active, Instant checkedAt) {
    }

    private final TokenService tokens;
    private final ServerSettings settings;
    private final Db db;
    private final Clock clock;
    private final Map<UUID, CacheEntry> sessionCache = new ConcurrentHashMap<>();

    public ApiAuthenticator(TokenService tokens, ServerSettings settings, Db db, Clock clock) {
        this.tokens = tokens;
        this.settings = settings;
        this.db = db;
        this.clock = clock;
    }

    public Optional<Principal> authenticate(HttpServletRequest request) {
        if (settings.developmentAuthenticationEnabled() && request.getHeader(USER_HEADER) != null) {
            return development(request);
        }
        var header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return bearer(header.substring(7).strip());
    }

    public Optional<Principal> bearer(String token) {
        var now = clock.now();
        var claims = tokens.validateAccessToken(token, now);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        var c = claims.get();
        try {
            var userId = UUID.fromString(c.getSubject());
            var organizationId = UUID.fromString(c.getStringClaim("organization_id"));
            var deviceId = UUID.fromString(c.getStringClaim("device_session_id"));
            var roles = listClaim(c.getClaim("role"));
            var permissions = new HashSet<>(listClaim(c.getClaim("permission")));
            var mfa = listClaim(c.getClaim("amr")).contains("mfa");
            return Optional.of(new Principal(userId, organizationId, deviceId, c.getStringClaim("email"),
                    c.getStringClaim("name"), roles, permissions, mfa, c.getExpirationTime().toInstant()));
        } catch (java.text.ParseException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Principal> development(HttpServletRequest request) {
        if (!settings.isTesting() && !ClientAddress.isLoopback(request)) {
            return Optional.empty();
        }
        var user = guid(request.getHeader(USER_HEADER));
        var organization = guid(request.getHeader(ORGANIZATION_HEADER));
        var device = guid(request.getHeader(DEVICE_HEADER));
        if (user == null || organization == null || device == null) {
            return Optional.empty();
        }
        return Optional.of(new Principal(user, organization, device, null, "Desenvolvimento",
                split(request.getHeader(ROLES_HEADER)), new HashSet<>(split(request.getHeader(PERMISSIONS_HEADER))),
                "true".equalsIgnoreCase(request.getHeader(MFA_HEADER)), null));
    }

    /** Sessão de dispositivo ativa e usuária em condições de acesso. */
    public boolean sessionActive(Principal principal) {
        var now = clock.now();
        var cached = sessionCache.get(principal.deviceSessionId());
        if (cached != null && cached.checkedAt.plus(SESSION_CACHE).isAfter(now)) {
            return cached.active;
        }
        boolean active = db.tenantRead(principal.organizationId(), c -> {
            // a FK garante que a usuária existe; bloqueio e inativação derrubam o acesso na hora (4.14)
            try (var st = c.prepareStatement("SELECT 1 FROM device_sessions s JOIN users u ON u.\"Id\" = s.\"UserId\" "
                    + "WHERE s.\"Id\" = ? AND s.\"UserId\" = ? AND s.\"OrganizationId\" = ? AND s.\"RevokedAtUtc\" IS NULL "
                    + "AND u.\"IsActive\" AND (NOT u.\"LockoutEnabled\" OR u.\"LockoutEnd\" IS NULL OR u.\"LockoutEnd\" <= now())")) {
                st.setObject(1, principal.deviceSessionId());
                st.setObject(2, principal.userId());
                st.setObject(3, principal.organizationId());
                try (var rs = st.executeQuery()) {
                    return rs.next();
                }
            }
        });
        if (sessionCache.size() > 10_000) {
            sessionCache.clear();
        }
        sessionCache.put(principal.deviceSessionId(), new CacheEntry(active, now));
        return active;
    }

    public void invalidate(UUID deviceSessionId) {
        sessionCache.remove(deviceSessionId);
    }

    public void invalidateAll() {
        sessionCache.clear();
    }

    private static List<String> listClaim(Object value) {
        if (value instanceof List<?> list) {
            var result = new ArrayList<String>();
            list.forEach(v -> result.add(String.valueOf(v)));
            return result;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private static List<String> split(String value) {
        return value == null ? List.of()
                : Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static UUID guid(String value) {
        try {
            var id = value == null ? null : UUID.fromString(value.strip());
            return id == null || id.equals(new UUID(0, 0)) ? null : id;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
