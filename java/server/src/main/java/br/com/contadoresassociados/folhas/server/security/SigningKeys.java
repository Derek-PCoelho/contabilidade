package br.com.contadoresassociados.folhas.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Chaves de assinatura dos tokens (RS256).
 *
 * <p>Pendência 4.1: o access token passa a ser um JWT <b>assinado</b> (JWS), não um JWE cifrado
 * como no OpenIddict padrão. Assim o Desktop consegue ler organização, usuária e permissões e
 * a API valida a assinatura. Em produção a chave vem do mesmo PKCS#12 configurado para o .NET
 * ({@code folhas.oidc.signing-certificate-path/-password}); certificados vencidos ou que vencem
 * em menos de 30 dias são recusados, como antes.
 */
public final class SigningKeys {

    private final RSAKey current;
    private final List<RSAKey> published;

    private SigningKeys(RSAKey current, List<RSAKey> published) {
        this.current = current;
        this.published = List.copyOf(published);
    }

    public static SigningKeys ephemeral() {
        try {
            var key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint(true).generate();
            return new SigningKeys(key, List.of(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Não foi possível gerar a chave efêmera de assinatura.", e);
        }
    }

    public static SigningKeys fromPkcs12(Path path, String password, Instant now) {
        if (path == null || password == null || password.isBlank() || !Files.isRegularFile(path)) {
            throw new IllegalStateException("O certificado de assinatura OIDC de produção não está configurado.");
        }
        try (InputStream in = Files.newInputStream(path)) {
            var store = KeyStore.getInstance("PKCS12");
            store.load(in, password.toCharArray());
            for (var alias : Collections.list(store.aliases())) {
                if (!store.isKeyEntry(alias)) {
                    continue;
                }
                var certificate = (X509Certificate) store.getCertificate(alias);
                if (certificate.getNotBefore().toInstant().isAfter(now)
                        || !certificate.getNotAfter().toInstant().isAfter(now.plus(Duration.ofDays(30)))) {
                    throw new IllegalStateException(
                            "O certificado de assinatura OIDC é inválido ou vence em menos de 30 dias.");
                }
                var privateKey = (RSAPrivateKey) store.getKey(alias, password.toCharArray());
                var key = new RSAKey.Builder((RSAPublicKey) certificate.getPublicKey()).privateKey(privateKey)
                        .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
                return new SigningKeys(key, List.of(key));
            }
            throw new IllegalStateException("O arquivo PKCS#12 não contém chave privada.");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível ler o certificado de assinatura OIDC.", e);
        }
    }

    public RSAKey current() {
        return current;
    }

    public List<RSAKey> verificationKeys() {
        return published;
    }

    public Map<String, Object> jwks() {
        return new JWKSet(published.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k.toPublicJWK()).toList())
                .toJSONObject(true);
    }
}
