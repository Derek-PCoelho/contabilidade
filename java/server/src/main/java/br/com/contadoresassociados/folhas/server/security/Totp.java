package br.com.contadoresassociados.folhas.server.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238, SHA-1, 6 dígitos, passo de 30 s), compatível com o
 * {@code AuthenticatorTokenProvider} do ASP.NET Identity: a chave é Base32 e fica em
 * {@code user_tokens} ({@code [AspNetUserStore]}/{@code AuthenticatorKey}).
 */
public final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int WINDOW = 1;

    private Totp() {
    }

    /** Chave nova de 160 bits em Base32 (mesmo tamanho gerado pelo Identity). */
    public static String newKey() {
        var bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /**
     * Confere o código; retorna o passo de tempo aceito, para que o chamador impeça reutilização
     * do mesmo código (o Identity não bloqueava replay dentro da janela).
     */
    public static OptionalLong verify(String base32Key, String code, Instant now) {
        if (base32Key == null || code == null) {
            return OptionalLong.empty();
        }
        var digits = code.replace(" ", "").replace("-", "");
        if (!digits.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        var key = decodeBase32(base32Key);
        var step = now.getEpochSecond() / 30;
        for (var offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantTimeEquals(generate(key, step + offset), digits)) {
                return OptionalLong.of(step + offset);
            }
        }
        return OptionalLong.empty();
    }

    public static String generate(byte[] key, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            var hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            var offset = hash[hash.length - 1] & 0x0f;
            var binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 indisponível.", e);
        }
    }

    public static String otpauthUri(String issuer, String account, String key) {
        var enc = (java.util.function.Function<String, String>) s -> java.net.URLEncoder
                .encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + enc.apply(issuer) + ":" + enc.apply(account) + "?secret=" + key + "&issuer="
                + enc.apply(issuer) + "&digits=6";
    }

    public static String base32(byte[] data) {
        var sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (var b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    public static byte[] decodeBase32(String value) {
        var clean = value.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        var out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (var ch : clean.toCharArray()) {
            var index = ALPHABET.indexOf(ch);
            if (index < 0) {
                throw new IllegalArgumentException("Chave Base32 inválida.");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
