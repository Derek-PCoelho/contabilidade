package br.com.contadoresassociados.folhas.infrastructure.updates;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Verificação Ed25519 do manifesto com chaves públicas fixadas no aplicativo (pendência 7.2).
 * Aceita mais de uma chave para permitir rotação sem quebrar instalações antigas.
 */
public final class SignedManifestVerifier {

    private final List<PublicKey> trustedKeys;

    public SignedManifestVerifier(List<String> trustedKeysBase64) {
        if (trustedKeysBase64 == null || trustedKeysBase64.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma chave de publicação confiável foi configurada.");
        }
        this.trustedKeys = trustedKeysBase64.stream().map(SignedManifestVerifier::decodeKey).toList();
    }

    /** Chave pública X.509 (SubjectPublicKeyInfo) em base64. */
    static PublicKey decodeKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Chave de publicação inválida.", e);
        }
    }

    public boolean verify(byte[] content, String signatureBase64) {
        if (content == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (var key : trustedKeys) {
            try {
                var verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(content);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException ignored) {
                // tenta a próxima chave
            }
        }
        return false;
    }

    /** Comparação de SHA-256 em tempo constante (o antigo ReleaseArtifactIntegrityVerifier, agora usado). */
    public static boolean matchesSha256(byte[] actual, String expectedHex) {
        if (expectedHex == null || expectedHex.length() != 64) {
            return false;
        }
        byte[] expected;
        try {
            expected = java.util.HexFormat.of().parseHex(expectedHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(actual, expected);
    }
}
