package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.server.security.TokenService;
import br.com.contadoresassociados.folhas.server.web.ClientAddress;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cookie de login do navegador (equivalente ao {@code __Host-FolhasMichelly.Identity}), um JWT
 * assinado com o {@code SecurityStamp} da usuária. Etapas:
 * {@code session} (login completo), {@code mfa} (senha conferida, falta o código) e
 * {@code enroll} (perfil privilegiado sem autenticador: só pode cadastrar o 2FA — pendência 4.4).
 *
 * <p>CSRF por double-submit: cookie aleatório {@code SameSite=Strict} + campo oculto.
 */
final class LoginSessions {

    static final JOSEObjectType TYPE = new JOSEObjectType("folhas-login+jwt");
    static final String SESSION = "session";
    static final String MFA_PENDING = "mfa";
    static final String ENROLL = "enroll";

    record Login(UUID userId, String stamp, String stage, List<String> amr, Instant mfaAt, Instant expiresAt) {
        boolean has(String method) {
            return amr.contains(method);
        }
    }

    private final TokenService tokens;

    LoginSessions(TokenService tokens) {
        this.tokens = tokens;
    }

    static String cookieName(HttpServletRequest request, String base) {
        return ClientAddress.secure(request) ? "__Host-" + base : base;
    }

    void issue(HttpServletRequest request, HttpServletResponse response, UUID userId, String stamp, String stage,
            List<String> amr, Instant mfaAt, Instant now, Duration lifetime) {
        var claims = new JWTClaimsSet.Builder().issuer(tokens.issuer()).subject(userId.toString())
                .claim("stamp", stamp).claim("stage", stage).claim("amr", amr)
                .claim("mfa_at", mfaAt == null ? null : mfaAt.getEpochSecond())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime))).build();
        set(request, response, "FolhasMichelly.Identity", tokens.sign(TYPE, claims), (int) lifetime.toSeconds(), "Lax");
    }

    Optional<Login> read(HttpServletRequest request, Instant now) {
        var value = cookie(request, cookieName(request, "FolhasMichelly.Identity"));
        if (value == null) {
            return Optional.empty();
        }
        return tokens.verify(value, TYPE, now).flatMap(c -> {
            try {
                var mfaAt = c.getLongClaim("mfa_at");
                return Optional.of(new Login(UUID.fromString(c.getSubject()), c.getStringClaim("stamp"),
                        c.getStringClaim("stage"), c.getStringListClaim("amr") == null ? List.of()
                                : c.getStringListClaim("amr"),
                        mfaAt == null ? null : Instant.ofEpochSecond(mfaAt), c.getExpirationTime().toInstant()));
            } catch (java.text.ParseException | RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        set(request, response, "FolhasMichelly.Identity", "", 0, "Lax");
    }

    String csrf(HttpServletRequest request, HttpServletResponse response) {
        var existing = cookie(request, cookieName(request, "FolhasMichelly.Csrf"));
        if (existing != null && existing.length() == 43) {
            return existing;
        }
        var token = RefreshTokenStore.newSecret();
        set(request, response, "FolhasMichelly.Csrf", token, 2 * 60 * 60, "Strict");
        return token;
    }

    boolean csrfValid(HttpServletRequest request, String submitted) {
        var expected = cookie(request, cookieName(request, "FolhasMichelly.Csrf"));
        return expected != null && submitted != null
                && MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        submitted.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void set(HttpServletRequest request, HttpServletResponse response, String base, String value,
            int maxAge, String sameSite) {
        var secure = ClientAddress.secure(request);
        var cookie = new Cookie(cookieName(request, base), value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", sameSite);
        response.addCookie(cookie);
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }
}
