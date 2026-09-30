package br.com.contadoresassociados.folhas.server.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hash de senha compatível com o {@code PasswordHasher<TUser>} do ASP.NET Core Identity
 * (formatos V2 e V3), para que as senhas já cadastradas continuem válidas.
 *
 * <p>V3: {@code 0x01 | prf(u32) | iterações(u32) | tamanhoSal(u32) | sal | subchave}.
 * Novos hashes usam PBKDF2-HMAC-SHA512 com 210 000 iterações; hashes antigos são
 * sinalizados para regravação no próximo login bem-sucedido.
 */
public final class PasswordHasher {

    public enum Result { FAILED, SUCCESS, SUCCESS_REHASH_NEEDED }

    public static final int ITERATIONS = 210_000;
    private static final int SALT_SIZE = 16;
    private static final int SUBKEY_SIZE = 32;
    private static final int PRF_SHA1 = 0;
    private static final int PRF_SHA256 = 1;
    private static final int PRF_SHA512 = 2;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash fixo usado quando a usuária não existe (pendência 4.12: tempo constante). */
    private static final String DUMMY = new PasswordHasher().hash("folhas-dummy-password-for-timing");

    public String hash(String password) {
        var salt = new byte[SALT_SIZE];
        RANDOM.nextBytes(salt);
        var subkey = pbkdf2("PBKDF2WithHmacSHA512", password, salt, ITERATIONS, SUBKEY_SIZE);
        var buffer = ByteBuffer.allocate(13 + SALT_SIZE + SUBKEY_SIZE);
        buffer.put((byte) 0x01).putInt(PRF_SHA512).putInt(ITERATIONS).putInt(SALT_SIZE).put(salt).put(subkey);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public Result verify(String hashed, String password) {
        if (hashed == null || password == null) {
            return Result.FAILED;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(hashed);
        } catch (IllegalArgumentException e) {
            return Result.FAILED;
        }
        if (decoded.length == 0) {
            return Result.FAILED;
        }
        return switch (decoded[0]) {
            case 0x00 -> verifyV2(decoded, password) ? Result.SUCCESS_REHASH_NEEDED : Result.FAILED;
            case 0x01 -> verifyV3(decoded, password);
            default -> Result.FAILED;
        };
    }

    /** Gasta o mesmo tempo de uma verificação real, para não revelar se o e-mail existe. */
    public void simulateVerification(String password) {
        verify(DUMMY, password == null ? "" : password);
    }

    private static boolean verifyV2(byte[] decoded, String password) {
        if (decoded.length != 1 + 16 + 32) {
            return false;
        }
        var salt = java.util.Arrays.copyOfRange(decoded, 1, 17);
        var expected = java.util.Arrays.copyOfRange(decoded, 17, 49);
        var actual = pbkdf2("PBKDF2WithHmacSHA1", password, salt, 1000, 32);
        return MessageDigest.isEqual(expected, actual);
    }

    private static Result verifyV3(byte[] decoded, String password) {
        if (decoded.length < 13) {
            return Result.FAILED;
        }
        var buffer = ByteBuffer.wrap(decoded, 1, decoded.length - 1);
        var prf = buffer.getInt();
        var iterations = buffer.getInt();
        var saltLength = buffer.getInt();
        if (saltLength < 16 || iterations < 1 || 13 + saltLength >= decoded.length) {
            return Result.FAILED;
        }
        var salt = new byte[saltLength];
        buffer.get(salt);
        var expected = new byte[decoded.length - 13 - saltLength];
        buffer.get(expected);
        if (expected.length < 16) {
            return Result.FAILED;
        }
        var algorithm = switch (prf) {
            case PRF_SHA1 -> "PBKDF2WithHmacSHA1";
            case PRF_SHA256 -> "PBKDF2WithHmacSHA256";
            case PRF_SHA512 -> "PBKDF2WithHmacSHA512";
            default -> null;
        };
        if (algorithm == null) {
            return Result.FAILED;
        }
        var actual = pbkdf2(algorithm, password, salt, iterations, expected.length);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Result.FAILED;
        }
        return prf != PRF_SHA512 || iterations < ITERATIONS ? Result.SUCCESS_REHASH_NEEDED : Result.SUCCESS;
    }

    private static byte[] pbkdf2(String algorithm, String password, byte[] salt, int iterations, int length) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, length * 8);
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível.", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Política de senha do Identity (mínimo 12 com maiúscula, minúscula, dígito e símbolo).
     * Retorna mensagens em português; lista vazia quando a senha é aceita.
     */
    public static java.util.List<String> validatePolicy(String password) {
        var errors = new java.util.ArrayList<String>();
        if (password == null || password.length() < 12) {
            errors.add("A senha precisa ter pelo menos 12 caracteres.");
            return errors;
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            errors.add("Inclua ao menos uma letra maiúscula.");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            errors.add("Inclua ao menos uma letra minúscula.");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            errors.add("Inclua ao menos um número.");
        }
        if (password.chars().allMatch(Character::isLetterOrDigit)) {
            errors.add("Inclua ao menos um símbolo.");
        }
        return errors;
    }
}
