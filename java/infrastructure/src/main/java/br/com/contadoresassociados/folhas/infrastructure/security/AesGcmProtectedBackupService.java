package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.security.ProtectedBackupService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope {@code .fdmbackup} byte-compatível com a versão .NET: JSON camelCase
 * {version, kdf, iterations, cipher, salt, nonce, tag, content}, PBKDF2-HMAC-SHA256 com 600.000
 * iterações sobre a senha em UTF-8, AES-256-GCM com AAD {@code FolhasDaMichelly.Backup.v1} e tag
 * separada do texto cifrado.
 */
public final class AesGcmProtectedBackupService implements ProtectedBackupService {

    static final int ITERATIONS = 600_000;
    static final int MAXIMUM_CONTENT_BYTES = 10 * 1024 * 1024;
    static final int MAXIMUM_ENVELOPE_BYTES = 20 * 1024 * 1024;
    static final int MINIMUM_PASSWORD_LENGTH = 12;
    static final byte[] ASSOCIATED_DATA = "FolhasDaMichelly.Backup.v1".getBytes(StandardCharsets.US_ASCII);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    private final SecureRandom random = new SecureRandom();

    record Envelope(@JsonProperty("version") int version, @JsonProperty("kdf") String kdf,
            @JsonProperty("iterations") int iterations, @JsonProperty("cipher") String cipher,
            @JsonProperty("salt") String salt, @JsonProperty("nonce") String nonce, @JsonProperty("tag") String tag,
            @JsonProperty("content") String content) {
    }

    @Override
    public byte[] protect(byte[] content, char[] password) {
        validatePassword(password);
        if (content == null || content.length == 0 || content.length > MAXIMUM_CONTENT_BYTES) {
            throw new ProtectedBackupException("O conteúdo da cópia está vazio ou excede 10 MB.");
        }
        var salt = new byte[16];
        var nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        var key = derive(password, salt, ITERATIONS);
        byte[] sealed = null;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            sealed = cipher.doFinal(content);
            var cipherText = Arrays.copyOfRange(sealed, 0, sealed.length - 16);
            var tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);
            var b64 = Base64.getEncoder();
            return MAPPER.writeValueAsBytes(new Envelope(1, "PBKDF2-HMAC-SHA256", ITERATIONS, "AES-256-GCM",
                    b64.encodeToString(salt), b64.encodeToString(nonce), b64.encodeToString(tag),
                    b64.encodeToString(cipherText)));
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new ProtectedBackupException("Não foi possível proteger a cópia.", e);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (sealed != null) {
                Arrays.fill(sealed, (byte) 0);
            }
        }
    }

    @Override
    public byte[] unprotect(byte[] envelope, char[] password) {
        validatePassword(password);
        if (envelope == null || envelope.length == 0 || envelope.length > MAXIMUM_ENVELOPE_BYTES) {
            throw invalid();
        }
        byte[] key = null;
        try {
            var doc = MAPPER.readValue(envelope, Envelope.class);
            if (doc == null || doc.version() != 1 || doc.iterations() != ITERATIONS
                    || !"PBKDF2-HMAC-SHA256".equals(doc.kdf()) || !"AES-256-GCM".equals(doc.cipher())) {
                throw invalid();
            }
            var b64 = Base64.getDecoder();
            var salt = b64.decode(doc.salt());
            var nonce = b64.decode(doc.nonce());
            var tag = b64.decode(doc.tag());
            var cipherText = b64.decode(doc.content());
            if (salt.length != 16 || nonce.length != 12 || tag.length != 16 || cipherText.length == 0
                    || cipherText.length > MAXIMUM_CONTENT_BYTES) {
                throw invalid();
            }
            key = derive(password, salt, doc.iterations());
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ASSOCIATED_DATA);
            var combined = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, combined, 0, cipherText.length);
            System.arraycopy(tag, 0, combined, cipherText.length, tag.length);
            return cipher.doFinal(combined);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException | NullPointerException e) {
            throw invalid();
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /** SunJCE converte a senha para UTF-8, igual ao {@code Rfc2898DeriveBytes.Pbkdf2(string, …)} do .NET. */
    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new ProtectedBackupException("PBKDF2 indisponível nesta plataforma.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < MINIMUM_PASSWORD_LENGTH || password.length > 256
                || new String(password).isBlank()) {
            throw new ProtectedBackupException("Use uma senha de cópia com pelo menos 12 caracteres.");
        }
    }

    private static ProtectedBackupException invalid() {
        return new ProtectedBackupException(
                "Não foi possível abrir a cópia. Confira a senha e a integridade do arquivo.");
    }
}
