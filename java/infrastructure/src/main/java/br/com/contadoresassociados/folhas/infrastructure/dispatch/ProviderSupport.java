package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Utilitários compartilhados pelos provedores de e-mail. */
final class ProviderSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s<>()\\[\\],;:\"]+@[^@\\s<>()\\[\\],;:\"]+\\.[^@\\s<>()\\[\\],;:\"]+$");

    private ProviderSupport() {
    }

    /** Anexo sumiu, mudou de tamanho ou de hash depois da aprovação. */
    static final class AttachmentChangedException extends IOException {
        AttachmentChangedException(String message) {
            super(message);
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256Hex(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[81_920];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Confere existência, tamanho e SHA-256 do anexo aprovado. */
    static void validateAttachment(DispatchAttachmentSnapshot attachment) throws IOException {
        if (attachment == null || attachment.localPath() == null) {
            throw new AttachmentChangedException("Attachment is missing.");
        }
        var path = Path.of(attachment.localPath());
        if (!Files.isRegularFile(path) || Files.size(path) != attachment.fileSizeBytes()) {
            throw new AttachmentChangedException("Attachment is missing or changed.");
        }
        if (!sha256Hex(path).equalsIgnoreCase(attachment.sha256())) {
            throw new AttachmentChangedException("Attachment hash changed.");
        }
    }

    /** Lê o anexo e confere os bytes lidos (evita corrida entre validar e ler). */
    static byte[] readValidated(DispatchAttachmentSnapshot attachment) throws IOException {
        var bytes = Files.readAllBytes(Path.of(attachment.localPath()));
        var hash = HexFormat.of().formatHex(sha256(bytes));
        if (bytes.length != attachment.fileSizeBytes() || !hash.equalsIgnoreCase(attachment.sha256())) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new AttachmentChangedException("Attachment changed before upload.");
        }
        return bytes;
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String randomBase64Url(int bytes) {
        var buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        try {
            return base64Url(buffer);
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Equivalente ao {@code Guid.ToString("N")} do .NET. */
    static String guidN(UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Nome do enum como no .NET ({@code DRAFT → Draft}). */
    static String pascal(Enum<?> value) {
        var parts = value.name().toLowerCase(Locale.ROOT).split("_");
        var sb = new StringBuilder();
        for (var part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /** Chave de idempotência gravada no provedor: {@code folhas:{Mode}:{itemIdN}:{fingerprint}}. */
    static String operationKey(DispatchOperationMode mode, UUID itemId, String fingerprint) {
        return "folhas:" + pascal(mode) + ":" + guidN(itemId) + ":" + fingerprint;
    }

    static boolean isEmail(String value) {
        return value != null && EMAIL.matcher(value.strip()).matches();
    }

    static EmailProviderResult permanent(String code, String message, String draftId) {
        return new EmailProviderResult(DeliveryAttemptState.FAILED_PERMANENT, null, draftId, code, message);
    }

    static EmailProviderResult transientFailure(String code, String message, String draftId) {
        return new EmailProviderResult(DeliveryAttemptState.FAILED_TRANSIENT, null, draftId, code, message);
    }

    static EmailProviderResult ambiguous(String code, String message, String draftId) {
        return new EmailProviderResult(DeliveryAttemptState.AMBIGUOUS, null, draftId, code, message);
    }
}
