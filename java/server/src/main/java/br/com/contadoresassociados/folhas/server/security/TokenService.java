package br.com.contadoresassociados.folhas.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Emissão e validação dos JWT assinados (access token e id token). */
public final class TokenService {

    public static final String AUDIENCE = "folhas_api";
    public static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final SigningKeys keys;
    private final String issuer;

    public TokenService(SigningKeys keys, String issuer) {
        this.keys = keys;
        this.issuer = issuer;
    }

    public String issuer() {
        return issuer;
    }

    public String sign(JOSEObjectType type, JWTClaimsSet claims) {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(type).keyID(keys.current().getKeyID()).build();
        var jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(keys.current()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Falha ao assinar o token.", e);
        }
        return jwt.serialize();
    }

    public String accessToken(TokenSubject subject, List<String> scopes, Instant now, Duration lifetime) {
        var claims = subject.claims(new JWTClaimsSet.Builder())
                .issuer(issuer)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .jwtID(UUID.randomUUID().toString())
                .claim("client_id", subject.clientId())
                .claim("scope", String.join(" ", scopes))
                .build();
        return sign(ACCESS_TOKEN_TYPE, claims);
    }

    public String idToken(TokenSubject subject, String nonce, Instant now, Duration lifetime) {
        var builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject.userId().toString())
                .audience(subject.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .claim("email", subject.email())
                .claim("name", subject.displayName())
                .claim("preferred_username", subject.userName())
                .claim("role", subject.roles());
        if (nonce != null && !nonce.isBlank()) {
            builder.claim("nonce", nonce);
        }
        return sign(JOSEObjectType.JWT, builder.build());
    }

    /** Valida assinatura, emissor, audiência, tipo e validade. */
    public Optional<JWTClaimsSet> validateAccessToken(String token, Instant now) {
        try {
            var jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !ACCESS_TOKEN_TYPE.equals(jwt.getHeader().getType())) {
                return Optional.empty();
            }
            var kid = jwt.getHeader().getKeyID();
            var key = keys.verificationKeys().stream().filter(k -> k.getKeyID().equals(kid)).findFirst();
            if (key.isEmpty() || !jwt.verify(new RSASSAVerifier(key.get().toRSAPublicKey()))) {
                return Optional.empty();
            }
            var claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer()) || claims.getAudience() == null
                    || !claims.getAudience().contains(AUDIENCE) || claims.getExpirationTime() == null) {
                return Optional.empty();
            }
            if (claims.getExpirationTime().toInstant().plus(CLOCK_SKEW).isBefore(now)) {
                return Optional.empty();
            }
            if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().minus(CLOCK_SKEW).isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Valida um JWT assinado por este servidor com o tipo informado (cookie de login, etc.). */
    public Optional<JWTClaimsSet> verify(String token, JOSEObjectType type, Instant now) {
        try {
            var jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !type.equals(jwt.getHeader().getType())) {
                return Optional.empty();
            }
            var kid = jwt.getHeader().getKeyID();
            var key = keys.verificationKeys().stream().filter(k -> k.getKeyID().equals(kid)).findFirst();
            if (key.isEmpty() || !jwt.verify(new RSASSAVerifier(key.get().toRSAPublicKey()))) {
                return Optional.empty();
            }
            var claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer()) || claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(now)) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Map<String, Object> jwks() {
        return keys.jwks();
    }

    /** Dados da usuária gravados no token. */
    public record TokenSubject(UUID userId, UUID organizationId, UUID deviceSessionId, String email, String userName,
            String displayName, List<String> roles, List<String> permissions, List<String> amr, String clientId) {

        JWTClaimsSet.Builder claims(JWTClaimsSet.Builder builder) {
            return builder.subject(userId.toString())
                    .claim("email", email)
                    .claim("name", displayName)
                    .claim("preferred_username", userName)
                    .claim("organization_id", organizationId.toString())
                    .claim("device_session_id", deviceSessionId.toString())
                    .claim("role", roles)
                    .claim("permission", permissions)
                    .claim("amr", amr);
        }
    }
}
