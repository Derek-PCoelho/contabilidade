package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.server.config.ServerSettings;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import br.com.contadoresassociados.folhas.server.security.PasswordHasher;
import br.com.contadoresassociados.folhas.server.security.QrCodes;
import br.com.contadoresassociados.folhas.server.security.Totp;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Login no navegador do sistema (fluxo OIDC do Desktop). Correções em relação ao .NET:
 * <ul>
 * <li>4.4 — cadastro do autenticador (QR, chave, códigos de recuperação) em
 * {@code /account/2fa/setup}; perfis privilegiados sem 2FA entram direto no cadastro em vez de
 * ficarem sem acesso;</li>
 * <li>4.10 — erros re-renderizam o formulário com mensagem (antes: página em branco 401/403);</li>
 * <li>4.12 — e-mail inexistente gasta o mesmo tempo de uma verificação de senha;</li>
 * <li>4.14 — o cookie carrega o {@code SecurityStamp}; trocar senha/2FA ou bloquear a conta
 * invalida o login do navegador;</li>
 * <li>códigos TOTP não podem ser reutilizados na mesma janela.</li>
 * </ul>
 */
@Controller
@RequestMapping("/account")
@Endpoint(anonymous = true, rate = Policy.AUTHENTICATION)
public class AccountController {

    private static final String GENERIC_FAILURE = "E-mail ou senha incorretos.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final Clock clock;
    private final ServerSettings settings;
    private final UserStore users = new UserStore();
    private final PasswordHasher hasher = new PasswordHasher();
    private final LoginSessions sessions;
    private final RefreshTokenStore refreshTokens;
    private final ApiAuthenticator authenticator;

    public AccountController(Db db, Clock clock, ServerSettings settings,
            br.com.contadoresassociados.folhas.server.security.TokenService tokens, RefreshTokenStore refreshTokens,
            ApiAuthenticator authenticator) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.sessions = new LoginSessions(tokens);
        this.refreshTokens = refreshTokens;
        this.authenticator = authenticator;
    }

    // ================================================================== senha

    @GetMapping("/login")
    public void loginForm(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(required = false) String returnUrl) throws IOException {
        renderLogin(request, response, 200, safeReturnUrl(returnUrl), null, "");
    }

    @PostMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String password,
            @RequestParam(required = false) String returnUrl, @RequestParam(name = "_csrf", required = false) String csrf)
            throws IOException {
        var target = safeReturnUrl(returnUrl);
        if (!sessions.csrfValid(request, csrf)) {
            renderLogin(request, response, 400, target, "A página expirou. Tente entrar novamente.", email);
            return;
        }
        var now = clock.now();
        var user = db.system(Db.Isolation.READ_COMMITTED, c -> users.findByEmail(c, email)).orElse(null);
        if (user == null) {
            hasher.simulateVerification(password);
            renderLogin(request, response, 401, target, GENERIC_FAILURE, email);
            return;
        }
        if (user.lockedOut(now)) {
            hasher.simulateVerification(password);
            renderLogin(request, response, 423, target, "A conta está bloqueada temporariamente por tentativas "
                    + "incorretas. Aguarde 15 minutos ou fale com a administração.", email);
            return;
        }
        var verification = hasher.verify(user.passwordHash(), password);
        if (verification == PasswordHasher.Result.FAILED) {
            db.system(Db.Isolation.READ_COMMITTED, c -> {
                users.accessFailed(c, user.id(), now);
                audit(c, user, "login_failed", "warning");
                return null;
            });
            renderLogin(request, response, 401, target, GENERIC_FAILURE, email);
            return;
        }
        if (!user.active() || !user.emailConfirmed()) {
            renderLogin(request, response, 403, target, "Esta conta não está liberada para acesso. Fale com a "
                    + "administração do escritório.", email);
            return;
        }
        var rehash = verification == PasswordHasher.Result.SUCCESS_REHASH_NEEDED ? hasher.hash(password) : null;
        if (user.twoFactorEnabled()) {
            if (rehash != null) {
                db.system(Db.Isolation.READ_COMMITTED, c -> {
                    users.signedIn(c, user.id(), now, rehash);
                    return null;
                });
            }
            var fresh = reload(user.id());
            sessions.issue(request, response, user.id(), fresh.securityStamp(), LoginSessions.MFA_PENDING,
                    List.of("pwd"), null, now, java.time.Duration.ofMinutes(10));
            response.sendRedirect("/account/login/mfa?returnUrl=" + encode(target));
            return;
        }
        db.system(Db.Isolation.READ_COMMITTED, c -> {
            users.signedIn(c, user.id(), now, rehash);
            audit(c, user, "login", "information");
            return null;
        });
        var fresh = reload(user.id());
        if (fresh.privileged()) {
            // perfil privilegiado sem autenticador: só pode cadastrar o 2FA (4.4)
            sessions.issue(request, response, user.id(), fresh.securityStamp(), LoginSessions.ENROLL, List.of("pwd"),
                    null, now, java.time.Duration.ofMinutes(15));
            response.sendRedirect("/account/2fa/setup?returnUrl=" + encode(target));
            return;
        }
        sessions.issue(request, response, user.id(), fresh.securityStamp(), LoginSessions.SESSION, List.of("pwd"), null,
                now, settings.loginSessionLifetime());
        response.sendRedirect(target);
    }

    // ================================================================== segundo fator

    @GetMapping("/login/mfa")
    public void mfaForm(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(required = false) String returnUrl) throws IOException {
        var target = safeReturnUrl(returnUrl);
        if (current(request, LoginSessions.MFA_PENDING).isEmpty()) {
            response.sendRedirect("/account/login?returnUrl=" + encode(target));
            return;
        }
        renderMfa(request, response, 200, target, null);
    }

    @PostMapping("/login/mfa")
    public void mfa(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(defaultValue = "") String code, @RequestParam(required = false) String returnUrl,
            @RequestParam(name = "_csrf", required = false) String csrf) throws IOException {
        var target = safeReturnUrl(returnUrl);
        var pending = current(request, LoginSessions.MFA_PENDING);
        if (pending.isEmpty()) {
            response.sendRedirect("/account/login?returnUrl=" + encode(target));
            return;
        }
        if (!sessions.csrfValid(request, csrf)) {
            renderMfa(request, response, 400, target, "A página expirou. Digite o código novamente.");
            return;
        }
        var user = pending.get();
        var now = clock.now();
        var accepted = db.system(Db.Isolation.SERIALIZABLE, c -> {
            if (verifySecondFactor(c, user, code, now)) {
                users.signedIn(c, user.id(), now, null);
                audit(c, user, "login_mfa", "information");
                return true;
            }
            users.accessFailed(c, user.id(), now);
            audit(c, user, "mfa_failed", "warning");
            return false;
        });
        if (!accepted) {
            var locked = reload(user.id()).lockedOut(now);
            renderMfa(request, response, locked ? 423 : 401, target, locked
                    ? "A conta foi bloqueada temporariamente por tentativas incorretas. Aguarde 15 minutos."
                    : "Código incorreto ou já utilizado. Confira o aplicativo autenticador e tente de novo.");
            return;
        }
        var fresh = reload(user.id());
        sessions.issue(request, response, user.id(), fresh.securityStamp(), LoginSessions.SESSION, List.of("pwd", "mfa"),
                now, now, settings.loginSessionLifetime());
        response.sendRedirect(target);
    }

    // ================================================================== cadastro do autenticador (4.4)

    @GetMapping("/2fa/setup")
    public void setupForm(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(required = false) String returnUrl) throws IOException {
        var target = safeReturnUrl(returnUrl);
        var user = enrollableUser(request);
        if (user.isEmpty()) {
            response.sendRedirect("/account/login?returnUrl=" + encode("/account/2fa/setup?returnUrl=" + encode(target)));
            return;
        }
        renderSetup(request, response, 200, target, user.get(), null);
    }

    @PostMapping("/2fa/setup")
    public void setup(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(defaultValue = "") String code, @RequestParam(required = false) String returnUrl,
            @RequestParam(name = "_csrf", required = false) String csrf) throws IOException {
        var target = safeReturnUrl(returnUrl);
        var found = enrollableUser(request);
        if (found.isEmpty()) {
            response.sendRedirect("/account/login?returnUrl=" + encode(target));
            return;
        }
        var user = found.get();
        if (!sessions.csrfValid(request, csrf)) {
            renderSetup(request, response, 400, target, user, "A página expirou. Digite o código novamente.");
            return;
        }
        var now = clock.now();
        var codes = db.system(Db.Isolation.SERIALIZABLE, c -> {
            var key = users.token(c, user.id(), UserStore.AUTHENTICATOR_KEY).orElse(null);
            var step = Totp.verify(key, code, now);
            if (step.isEmpty()) {
                users.accessFailed(c, user.id(), now);
                return null;
            }
            users.setToken(c, user.id(), UserStore.LAST_TOTP_STEP, Long.toString(step.getAsLong()));
            var fresh = users.findById(c, user.id()).orElseThrow();
            if (!users.update(c, fresh, fresh.passwordHash(), true)) {
                throw Db.retry(new SQLException("usuária alterada", "40001"));
            }
            var recovery = newRecoveryCodes();
            users.setToken(c, user.id(), UserStore.RECOVERY_CODES, recovery.stream().map(r -> "sha256$"
                    + RefreshTokenStore.hash(normalizeRecovery(r))).collect(Collectors.joining(";")));
            refreshTokens.revokeUser(c, user.id());
            audit(c, user, "mfa_enabled", "warning");
            return recovery;
        });
        if (codes == null) {
            renderSetup(request, response, 401, target, user, "O código não confere. Verifique se o relógio do celular "
                    + "está correto e tente o código mais recente.");
            return;
        }
        authenticator.invalidateAll();
        var fresh = reload(user.id());
        sessions.issue(request, response, user.id(), fresh.securityStamp(), LoginSessions.SESSION, List.of("pwd", "mfa"),
                now, now, settings.loginSessionLifetime());
        var list = codes.stream().map(r -> "<li>" + Pages.escape(r) + "</li>").collect(Collectors.joining());
        Pages.render(response, 200, "Autenticador cadastrado", "<p>O aplicativo autenticador foi cadastrado.</p>"
                + "<p><strong>Guarde estes códigos de recuperação</strong> em local seguro. Cada um funciona uma única vez "
                + "caso o celular seja perdido. Eles não serão mostrados novamente.</p><ul class=\"codes\">" + list
                + "</ul><a class=\"button\" href=\"" + Pages.escape(target) + "\">Continuar</a>");
    }

    // ================================================================== saída

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "_csrf", required = false) String csrf) throws IOException {
        if (sessions.csrfValid(request, csrf)) {
            sessions.clear(request, response);
        }
        Pages.render(response, 200, "Sessão encerrada", "<p>Você saiu da conta neste navegador.</p>");
    }

    // ================================================================== apoio

    /** Login do navegador válido: assinatura, validade, etapa, stamp atual e conta liberada (4.14). */
    Optional<UserRecord> current(HttpServletRequest request, String stage) {
        var now = clock.now();
        var login = sessions.read(request, now).filter(l -> l.stage().equals(stage));
        if (login.isEmpty()) {
            return Optional.empty();
        }
        var user = db.system(Db.Isolation.READ_COMMITTED, c -> users.findById(c, login.get().userId())).orElse(null);
        if (user == null || !user.canSignIn(now) || !java.util.Objects.equals(user.securityStamp(), login.get().stamp())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    Optional<LoginSessions.Login> currentLogin(HttpServletRequest request) {
        return sessions.read(request, clock.now());
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        sessions.clear(request, response);
    }

    private Optional<UserRecord> enrollableUser(HttpServletRequest request) {
        var enrolling = current(request, LoginSessions.ENROLL);
        if (enrolling.isPresent()) {
            return enrolling;
        }
        // usuária já logada pode (re)cadastrar o autenticador somente se ainda não tiver 2FA
        return current(request, LoginSessions.SESSION).filter(u -> !u.twoFactorEnabled());
    }

    private boolean verifySecondFactor(Connection c, UserRecord user, String code, Instant now) throws SQLException {
        var clean = code == null ? "" : code.strip();
        if (clean.replace(" ", "").matches("\\d{6}")) {
            var key = users.token(c, user.id(), UserStore.AUTHENTICATOR_KEY).orElse(null);
            var step = Totp.verify(key, clean, now);
            if (step.isEmpty()) {
                return false;
            }
            var last = users.token(c, user.id(), UserStore.LAST_TOTP_STEP).map(Long::parseLong).orElse(Long.MIN_VALUE);
            if (step.getAsLong() <= last) {
                return false;
            }
            users.setToken(c, user.id(), UserStore.LAST_TOTP_STEP, Long.toString(step.getAsLong()));
            return true;
        }
        return redeemRecoveryCode(c, user.id(), clean);
    }

    /** Aceita códigos gravados pelo .NET (texto) e pelo servidor Java ({@code sha256$…}). */
    private boolean redeemRecoveryCode(Connection c, UUID userId, String code) throws SQLException {
        var stored = users.token(c, userId, UserStore.RECOVERY_CODES).orElse("");
        if (stored.isBlank() || code.isBlank()) {
            return false;
        }
        var normalized = normalizeRecovery(code);
        var hashed = "sha256$" + RefreshTokenStore.hash(normalized);
        var remaining = new ArrayList<>(Arrays.asList(stored.split(";")));
        var match = remaining.stream().filter(e -> e.equals(hashed) || normalizeRecovery(e).equals(normalized)
                && !e.startsWith("sha256$")).findFirst();
        if (match.isEmpty()) {
            return false;
        }
        remaining.remove(match.get());
        users.setToken(c, userId, UserStore.RECOVERY_CODES, String.join(";", remaining));
        return true;
    }

    private static String normalizeRecovery(String code) {
        return code.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static List<String> newRecoveryCodes() {
        var alphabet = "BCDFGHJKMNPQRSTVWXYZ23456789";
        var list = new ArrayList<String>();
        for (var i = 0; i < 10; i++) {
            var sb = new StringBuilder();
            for (var j = 0; j < 10; j++) {
                if (j == 5) {
                    sb.append('-');
                }
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            list.add(sb.toString());
        }
        return list;
    }

    private UserRecord reload(UUID id) {
        return db.system(Db.Isolation.READ_COMMITTED, c -> users.findById(c, id)).orElseThrow();
    }

    private void audit(Connection c, UserRecord user, String action, String severity) throws SQLException {
        AuditLog.append(c, user.organizationId(), user.id(), null, "user", user.id().toString(), action,
                "authentication", severity, Map.of(), clock.now(), UUID.randomUUID());
    }

    /** Pendência 4.2/4.10: só aceita retorno para o próprio servidor (sem open redirect). */
    static String safeReturnUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 4000) {
            return "/";
        }
        if (!value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\") || value.contains("\r")
                || value.contains("\n")) {
            return "/";
        }
        return value;
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void renderLogin(HttpServletRequest request, HttpServletResponse response, int status, String returnUrl,
            String error, String email) throws IOException {
        var csrf = sessions.csrf(request, response);
        Pages.render(response, status, "Entrar", "<p>Autenticação segura do aplicativo.</p>" + Pages.error(error)
                + "<form method=\"post\" action=\"/account/login\">" + Pages.hidden("_csrf", csrf)
                + Pages.hidden("returnUrl", returnUrl)
                + "<label for=\"email\">E-mail</label><input id=\"email\" type=\"email\" name=\"email\" "
                + "autocomplete=\"username\" required value=\"" + Pages.escape(email) + "\">"
                + "<label for=\"password\">Senha</label><input id=\"password\" type=\"password\" name=\"password\" "
                + "autocomplete=\"current-password\" required><button type=\"submit\">Entrar</button></form>");
    }

    private void renderMfa(HttpServletRequest request, HttpServletResponse response, int status, String returnUrl,
            String error) throws IOException {
        var csrf = sessions.csrf(request, response);
        Pages.render(response, status, "Segundo fator", "<p>Digite o código de 6 dígitos do aplicativo autenticador "
                + "ou um código de recuperação.</p>" + Pages.error(error) + "<form method=\"post\" "
                + "action=\"/account/login/mfa\">" + Pages.hidden("_csrf", csrf) + Pages.hidden("returnUrl", returnUrl)
                + "<label for=\"code\">Código</label><input id=\"code\" name=\"code\" inputmode=\"numeric\" "
                + "autocomplete=\"one-time-code\" required autofocus><button type=\"submit\">Confirmar</button></form>");
    }

    private void renderSetup(HttpServletRequest request, HttpServletResponse response, int status, String returnUrl,
            UserRecord user, String error) throws IOException {
        var key = db.system(Db.Isolation.READ_COMMITTED, c -> {
            var existing = users.token(c, user.id(), UserStore.AUTHENTICATOR_KEY);
            if (existing.isPresent() && !existing.get().isBlank()) {
                return existing.get();
            }
            var generated = Totp.newKey();
            users.setToken(c, user.id(), UserStore.AUTHENTICATOR_KEY, generated);
            return generated;
        });
        var account = user.email() == null ? user.userName() : user.email();
        var uri = Totp.otpauthUri("Folhas da Michelly", account, key);
        var grouped = key.replaceAll("(.{4})", "$1 ").strip();
        var csrf = sessions.csrf(request, response);
        Pages.render(response, status, "Cadastrar autenticador", "<p>Seu perfil exige o segundo fator. Abra o aplicativo "
                + "autenticador (Microsoft Authenticator, Google Authenticator ou similar), escaneie o código abaixo e "
                + "digite os 6 dígitos exibidos.</p><img alt=\"QR code do autenticador\" src=\"" + QrCodes.svgDataUri(uri)
                + "\"><p class=\"muted\">Sem câmera? Digite a chave: <code>" + Pages.escape(grouped) + "</code></p>"
                + Pages.error(error) + "<form method=\"post\" action=\"/account/2fa/setup\">" + Pages.hidden("_csrf", csrf)
                + Pages.hidden("returnUrl", returnUrl) + "<label for=\"code\">Código do aplicativo</label>"
                + "<input id=\"code\" name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" required>"
                + "<button type=\"submit\">Ativar segundo fator</button></form>");
    }
}
