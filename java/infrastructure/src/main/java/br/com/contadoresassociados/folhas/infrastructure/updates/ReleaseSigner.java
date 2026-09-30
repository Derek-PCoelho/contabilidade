package br.com.contadoresassociados.folhas.infrastructure.updates;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Ferramenta de publicação (usada no CI, nunca no aplicativo):
 * <pre>
 *   java ... ReleaseSigner keygen                         → imprime chave privada (PKCS#8) e pública (X.509)
 *   java ... ReleaseSigner sign releases-win-x64-stable.json  (chave em FOLHAS_RELEASE_SIGNING_KEY)
 * </pre>
 * A chave privada fica só no cofre do CI; a pública vai fixada na configuração do aplicativo.
 */
public final class ReleaseSigner {

    private ReleaseSigner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "keygen".equals(args[0])) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            System.out.println("private=" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            System.out.println("public=" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return;
        }
        if (args.length == 2 && "sign".equals(args[0])) {
            var key = System.getenv("FOLHAS_RELEASE_SIGNING_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Defina FOLHAS_RELEASE_SIGNING_KEY.");
            }
            var file = Path.of(args[1]);
            Files.writeString(file.resolveSibling(file.getFileName() + ".sig"),
                    sign(Files.readAllBytes(file), key), StandardCharsets.US_ASCII);
            return;
        }
        System.err.println("uso: ReleaseSigner keygen | ReleaseSigner sign <manifesto.json>");
        System.exit(2);
    }

    public static String sign(byte[] content, String privateKeyBase64) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(content);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
