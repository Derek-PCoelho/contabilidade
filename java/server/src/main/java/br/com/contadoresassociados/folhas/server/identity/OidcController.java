package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import br.com.contadoresassociados.folhas.domain.identity.AppRole;
import br.com.contadoresassociados.folhas.domain.identity.RolePermissionCatalog;
import br.com.contadoresassociados.folhas.server.config.ServerSettings;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.db.Sql;
import br.com.contadoresassociados.folhas.server.security.TokenService;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Servidor OAuth 2.0 / OpenID Connect para o Desktop (substitui o OpenIddict), nas mesmas rotas
 * ({@code connect/authorize|token|logout|revoke}), com código de autorização + PKCE S256
 * obrigatório, access token de 10 minutos e refresh rotativo de 30 dias.
 *
 * <p>Correções: 4.1 (JWT assinado legível pelo Desktop), 4.3 (refresh recarrega a usuária,
 * compara o {@code SecurityStamp}, confere bloqueio/ativo/sessão e deixa o MFA expirar),
 * 4.9 (sessão revogada no authorize devolve {@code access_denied} ao cliente em vez de 500).
 */
@Controller
public class OidcController {

    private static final Set<String> SUPPORTED_SCOPES = Set.of("openid", "offline_access", "email", "profile", "roles",
            "folhas_api");
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);

    private final Db db;
    private final Clock clock;
    private final ServerSettings settings;
    private final TokenService tokens;
    private final RefreshTokenStore store;
    private final OidcClients clients = new OidcClients();
    private final UserStore users = new UserStore();
    private final AccountController account;

    public OidcController(Db db, Clock clock, ServerSettings settings, TokenService tokens, RefreshTokenStore store,
            AccountController account) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.tokens = tokens;
        this.store = store;
        this.account = account;
    }

    // ================================================================== descoberta

    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @Endpoint(anonymous = true, rate = Policy.READ)
    public Map<String, Object> discovery() {
        var issuer = tokens.issuer();
        var doc = new LinkedHashMap<String, Object>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/connect/authorize");
        doc.put("token_endpoint", issuer + "/connect/token");
        doc.put("revocation_endpoint", issuer + "/connect/revoke");
        doc.put("end_session_endpoint", issuer + "/connect/logout");
        doc.put("userinfo_endpoint", issuer + "/connect/userinfo");
        doc.put("jwks_uri", issuer + "/.well-known/jwks");
        doc.put("response_types_supported", List.of("code"));
        doc.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("scopes_supported", List.copyOf(SUPPORTED_SCOPES));
        doc.put("token_endpoint_auth_methods_supported", List.of("none"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("authorization_response_iss_parameter_supported", true);
        return doc;
    }

    @GetMapping(value = "/.well-known/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @Endpoint(anonymous = true, rate = Policy.READ)
    public Map<String, Object> jwks() {
        return tokens.jwks();
    }

    // ================================================================== authorize

    @GetMapping("/connect/authorize")
    @Endpoint(anonymous = true, rate = Policy.AUTHENTICATION)
    public void authorize(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String state,
            @RequestParam(required = false) String nonce,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "device_name", required = false) String deviceName) throws IOException {
        var client = db.system(Db.Isolation.READ_COMMITTED, c -> clients.find(c, clientId)).orElse(null);
        if (client == null || !client.acceptsRedirect(redirectUri)) {
            // nunca redirecionar para um destino não registrado
            Pages.render(response, 400, "Solicitação inválida", Pages.error("O aplicativo que pediu o login não é "
                    + "reconhecido. Abra o login novamente pelo Folhas da Michelly."));
            return;
        }
        if (!"code".equals(responseType)) {
            redirectError(response, redirectUri, state, "unsupported_response_type", "Somente response_type=code.");
            return;
        }
        if (client.requiresPkce() && (codeChallenge == null || !"S256".equals(codeChallengeMethod)
                || !codeChallenge.matches("[A-Za-z0-9_-]{43,128}"))) {
            redirectError(response, redirectUri, state, "invalid_request", "PKCE S256 obrigatório.");
            return;
        }
        var scopes = scopes(scope);
        var login = account.current(request, LoginSessions.SESSION);
        if (login.isEmpty()) {
            account.clear(request, response);
            response.sendRedirect("/account/login?returnUrl=" + AccountController.encode(request.getRequestURI() + "?"
                    + request.getQueryString()));
            return;
        }
        var user = login.get();
        var loginInfo = account.currentLogin(request).orElseThrow();
        if (user.privileged() && !loginInfo.has("mfa")) {
            response.sendRedirect(user.twoFactorEnabled()
                    ? "/account/login?returnUrl=" + AccountController.encode(request.getRequestURI() + "?"
                            + request.getQueryString())
                    : "/account/2fa/setup?returnUrl=" + AccountController.encode(request.getRequestURI() + "?"
                            + request.getQueryString()));
            return;
        }
        var now = clock.now();
        var code = db.tenant(user.organizationId(), Db.Isolation.READ_COMMITTED, c -> {
            var session = resolveDeviceSession(c, user, deviceId, deviceName, now);
            if (session == null) {
                return null;
            }
            var grant = new RefreshTokenStore.Grant(user.id(), user.organizationId(), session, client.clientId(), scopes,
                    loginInfo.amr(), loginInfo.mfaAt(), user.securityStamp(), redirectUri, codeChallenge, nonce);
            return store.issue(c, client.id(), RefreshTokenStore.TYPE_CODE, grant, now, now.plus(CODE_LIFETIME));
        });
        if (code == null) {
            redirectError(response, redirectUri, state, "access_denied",
                    "Esta sessão de dispositivo foi encerrada. Entre novamente para criar outra.");
            return;
        }
        var params = new LinkedHashMap<String, String>();
        params.put("code", code);
        if (state != null) {
            params.put("state", state);
        }
        params.put("iss", tokens.issuer());
        response.sendRedirect(append(redirectUri, params));
    }

    /** Sessão existente ou nova; {@code null} quando o id pedido já foi revogado (4.9). */
    private UUID resolveDeviceSession(Connection c, UserRecord user, String deviceId, String deviceName, Instant now)
            throws SQLException {
        UUID id;
        try {
            id = deviceId == null || deviceId.isBlank() ? UUID.randomUUID() : UUID.fromString(deviceId.strip());
        } catch (IllegalArgumentException e) {
            id = UUID.randomUUID();
        }
        if (id.equals(new UUID(0, 0))) {
            id = UUID.randomUUID();
        }
        try (var st = c.prepareStatement("SELECT \"UserId\", \"RevokedAtUtc\" FROM device_sessions WHERE \"Id\" = ? "
                + "AND \"OrganizationId\" = ? FOR UPDATE")) {
            st.setObject(1, id);
            st.setObject(2, user.organizationId());
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    if (!user.id().equals(Sql.uuid(rs, "UserId")) || rs.getObject("RevokedAtUtc") != null) {
                        return null;
                    }
                    try (var up = c.prepareStatement("UPDATE device_sessions SET \"LastSeenAtUtc\" = ? WHERE \"Id\" = ?")) {
                        Sql.instant(up, 1, now);
                        up.setObject(2, id);
                        up.executeUpdate();
                    }
                    return id;
                }
            }
        }
        var name = deviceName == null || deviceName.isBlank() ? "Desktop" : deviceName.strip();
        if (name.length() > 160) {
            name = name.substring(0, 160);
        }
        var savepoint = c.setSavepoint();
        try (var st = c.prepareStatement("INSERT INTO device_sessions (\"Id\", \"OrganizationId\", \"UserId\", \"DeviceName\", "
                + "\"CreatedAtUtc\", \"LastSeenAtUtc\") VALUES (?, ?, ?, ?, ?, ?)")) {
            st.setObject(1, id);
            st.setObject(2, user.organizationId());
            st.setObject(3, user.id());
            st.setString(4, name);
            Sql.instant(st, 5, now);
            Sql.instant(st, 6, now);
            st.executeUpdate();
            c.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            if (!Db.isUniqueViolation(e)) {
                throw e;
            }
            // id pertence a outra organização: cria uma sessão nova em vez de reutilizar
            c.rollback(savepoint);
            return resolveDeviceSession(c, user, UUID.randomUUID().toString(), deviceName, now);
        }
        AuditLog.append(c, user.organizationId(), user.id(), id, "device_session", id.toString(), "created",
                "authentication", "information", Map.of(), now, UUID.randomUUID());
        return id;
    }

    // ================================================================== token

    @PostMapping(value = "/connect/token", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @Endpoint(anonymous = true, rate = Policy.AUTHENTICATION)
    public ResponseEntity<Map<String, Object>> token(@RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(required = false) String code, @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "code_verifier", required = false) String codeVerifier,
            @RequestParam(name = "refresh_token", required = false) String refreshToken) {
        var client = db.system(Db.Isolation.READ_COMMITTED, c -> clients.find(c, clientId)).orElse(null);
        if (client == null) {
            return error(401, "invalid_client", "Cliente não reconhecido.");
        }
        if ("authorization_code".equals(grantType)) {
            return exchangeCode(client, code, redirectUri, codeVerifier);
        }
        if ("refresh_token".equals(grantType)) {
            return refresh(client, refreshToken);
        }
        return error(400, "unsupported_grant_type", "Somente authorization_code e refresh_token.");
    }

    private ResponseEntity<Map<String, Object>> exchangeCode(OidcClients.OidcClient client, String code,
            String redirectUri, String verifier) {
        var now = clock.now();
        var outcome = db.system(Db.Isolation.READ_COMMITTED, c -> {
            var stored = store.find(c, RefreshTokenStore.TYPE_CODE, code).orElse(null);
            if (stored == null) {
                return Outcome.fail("O código de autorização é inválido.");
            }
            var grant = stored.grant();
            if (!RefreshTokenStore.STATUS_VALID.equals(stored.status())) {
                // código reapresentado: possível interceptação — encerra a sessão inteira
                store.revokeSession(c, grant.deviceSessionId(), now);
                return Outcome.fail("O código de autorização já foi usado.");
            }
            store.markRedeemed(c, stored.id(), now);
            if (stored.expiresAt().isBefore(now) || !client.clientId().equals(grant.clientId())
                    || !java.util.Objects.equals(grant.redirectUri(), redirectUri)
                    || (grant.codeChallenge() != null && !pkceMatches(grant.codeChallenge(), verifier))) {
                return Outcome.fail("O código de autorização é inválido para este aplicativo.");
            }
            return issueTokens(c, client, grant, now, false);
        });
        return outcome.response();
    }

    private ResponseEntity<Map<String, Object>> refresh(OidcClients.OidcClient client, String refreshToken) {
        var now = clock.now();
        var outcome = db.system(Db.Isolation.READ_COMMITTED, c -> {
            var stored = store.find(c, RefreshTokenStore.TYPE_REFRESH, refreshToken).orElse(null);
            if (stored == null) {
                return Outcome.fail("A sessão expirou. Entre novamente.");
            }
            var grant = stored.grant();
            if (RefreshTokenStore.STATUS_REDEEMED.equals(stored.status())) {
                store.revokeSession(c, grant.deviceSessionId(), now);
                AuditLog.append(c, grant.organizationId(), grant.userId(), grant.deviceSessionId(), "device_session",
                        grant.deviceSessionId().toString(), "refresh_reuse_detected", "authentication", "critical",
                        Map.of(), now, UUID.randomUUID());
                return Outcome.fail("A sessão foi encerrada por segurança. Entre novamente.");
            }
            if (!RefreshTokenStore.STATUS_VALID.equals(stored.status()) || stored.expiresAt().isBefore(now)
                    || !client.clientId().equals(grant.clientId())) {
                return Outcome.fail("A sessão expirou. Entre novamente.");
            }
            store.markRedeemed(c, stored.id(), now);
            return issueTokens(c, client, grant, now, true);
        });
        return outcome.response();
    }

    private Outcome issueTokens(Connection c, OidcClients.OidcClient client, RefreshTokenStore.Grant grant, Instant now,
            boolean refreshing) throws SQLException {
        var user = users.findById(c, grant.userId()).orElse(null);
        // 4.3: a usuária é recarregada a cada troca; stamp diferente = senha/2FA/bloqueio alterados
        if (user == null || !user.canSignIn(now) || !java.util.Objects.equals(user.securityStamp(), grant.securityStamp())
                || !user.organizationId().equals(grant.organizationId())) {
            store.revokeSession(c, grant.deviceSessionId(), now);
            return Outcome.fail("A conta foi alterada ou bloqueada. Entre novamente.");
        }
        if (!sessionActive(c, grant)) {
            return Outcome.fail("Esta sessão de dispositivo foi encerrada. Entre novamente.");
        }
        var amr = new ArrayList<>(grant.amr());
        var mfaExpired = grant.mfaAt() == null || grant.mfaAt().plus(settings.mfaLifetime()).isBefore(now)
                || !user.twoFactorEnabled();
        if (mfaExpired) {
            amr.remove("mfa");
        }
        if (refreshing && user.privileged() && !amr.contains("mfa")) {
            // perfil privilegiado precisa refazer o segundo fator ao expirar
            return Outcome.fail("Confirme novamente com o segundo fator para continuar.");
        }
        var roles = user.roles();
        var permissions = RolePermissionCatalog.forRoles(roles.stream().map(AppRole::fromWireName)
                .flatMap(Optional::stream).toList()).stream().map(AppPermission::wireName).sorted().toList();
        var subject = new TokenService.TokenSubject(user.id(), user.organizationId(), grant.deviceSessionId(),
                user.email(), user.userName(), user.displayName(), roles, permissions, amr, client.clientId());
        var lifetime = settings.accessTokenLifetime();
        var body = new LinkedHashMap<String, Object>();
        body.put("access_token", tokens.accessToken(subject, grant.scopes(), now, lifetime));
        body.put("token_type", "Bearer");
        body.put("expires_in", lifetime.toSeconds());
        body.put("scope", String.join(" ", grant.scopes()));
        if (grant.scopes().contains("offline_access")) {
            var next = new RefreshTokenStore.Grant(grant.userId(), grant.organizationId(), grant.deviceSessionId(),
                    grant.clientId(), grant.scopes(), amr, mfaExpired ? null : grant.mfaAt(), grant.securityStamp(),
                    null, null, null);
            body.put("refresh_token", store.issue(c, client.id(), RefreshTokenStore.TYPE_REFRESH, next, now,
                    now.plus(settings.refreshTokenLifetime())));
        }
        if (!refreshing && grant.scopes().contains("openid")) {
            body.put("id_token", tokens.idToken(subject, grant.nonce(), now, lifetime));
        }
        try (var st = c.prepareStatement("UPDATE device_sessions SET \"LastSeenAtUtc\" = ? WHERE \"Id\" = ?")) {
            Sql.instant(st, 1, now);
            st.setObject(2, grant.deviceSessionId());
            st.executeUpdate();
        }
        return new Outcome(ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(body));
    }

    private static boolean sessionActive(Connection c, RefreshTokenStore.Grant grant) throws SQLException {
        try (var st = c.prepareStatement("SELECT 1 FROM device_sessions WHERE \"Id\" = ? AND \"UserId\" = ? AND "
                + "\"OrganizationId\" = ? AND \"RevokedAtUtc\" IS NULL")) {
            st.setObject(1, grant.deviceSessionId());
            st.setObject(2, grant.userId());
            st.setObject(3, grant.organizationId());
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record Outcome(ResponseEntity<Map<String, Object>> response) {
        static Outcome fail(String description) {
            return new Outcome(error(400, "invalid_grant", description));
        }
    }

    // ================================================================== revoke / logout / userinfo

    @PostMapping("/connect/revoke")
    @Endpoint(anonymous = true, rate = Policy.AUTHENTICATION)
    public ResponseEntity<Void> revoke(@RequestParam(required = false) String token,
            @RequestParam(name = "client_id", required = false) String clientId) {
        db.system(Db.Isolation.READ_COMMITTED, c -> {
            var stored = store.find(c, RefreshTokenStore.TYPE_REFRESH, token);
            if (stored.isPresent() && stored.get().grant().clientId().equals(clientId)) {
                store.revokeSession(c, stored.get().grant().deviceSessionId(), clock.now());
                store.revoke(c, stored.get().id());
            }
            return null;
        });
        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/connect/logout", method = {RequestMethod.GET, RequestMethod.POST})
    @Endpoint(anonymous = true, rate = Policy.AUTHENTICATION)
    public void logout(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "post_logout_redirect_uri", required = false) String postLogout,
            @RequestParam(required = false) String state) throws IOException {
        account.clear(request, response);
        var client = clientId == null ? Optional.<OidcClients.OidcClient>empty()
                : db.system(Db.Isolation.READ_COMMITTED, c -> clients.find(c, clientId));
        if (client.isPresent() && client.get().acceptsPostLogout(postLogout)) {
            var params = new LinkedHashMap<String, String>();
            if (state != null) {
                params.put("state", state);
            }
            response.sendRedirect(append(postLogout, params));
            return;
        }
        Pages.render(response, 200, "Sessão encerrada", "<p>Você saiu da conta. Pode fechar esta janela.</p>");
    }

    @GetMapping(value = "/connect/userinfo", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @Endpoint(rate = Policy.READ)
    public Map<String, Object> userinfo(HttpServletRequest request) {
        var user = RequestContext.require(request);
        var info = new LinkedHashMap<String, Object>();
        info.put("sub", user.userId().toString());
        info.put("email", user.email());
        info.put("name", user.displayName());
        info.put("organization_id", user.organizationId().toString());
        info.put("device_session_id", user.deviceSessionId().toString());
        info.put("role", user.roles());
        info.put("permission", user.permissions().stream().sorted().toList());
        info.put("amr", user.mfa() ? List.of("pwd", "mfa") : List.of("pwd"));
        return info;
    }

    // ================================================================== auxiliares

    static boolean pkceMatches(String challenge, String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) {
            return false;
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            var computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> scopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of("openid", "folhas_api");
        }
        var list = new ArrayList<String>();
        for (var s : scope.strip().split("\\s+")) {
            if (SUPPORTED_SCOPES.contains(s) && !list.contains(s)) {
                list.add(s);
            }
        }
        if (!list.contains("folhas_api")) {
            list.add("folhas_api");
        }
        return list;
    }

    private static void redirectError(HttpServletResponse response, String redirectUri, String state, String error,
            String description) throws IOException {
        var params = new LinkedHashMap<String, String>();
        params.put("error", error);
        params.put("error_description", description);
        if (state != null) {
            params.put("state", state);
        }
        response.sendRedirect(append(redirectUri, params));
    }

    private static String append(String uri, Map<String, String> params) {
        var sb = new StringBuilder(uri);
        var separator = uri.contains("?") ? '&' : '?';
        for (var entry : params.entrySet()) {
            sb.append(separator).append(AccountController.encode(entry.getKey())).append('=')
                    .append(AccountController.encode(entry.getValue()));
            separator = '&';
        }
        return sb.toString();
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String error, String description) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("error_description", description);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
