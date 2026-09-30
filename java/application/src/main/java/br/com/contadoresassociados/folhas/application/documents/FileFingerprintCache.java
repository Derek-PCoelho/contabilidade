package br.com.contadoresassociados.folhas.application.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache de SHA-256 por (caminho, tamanho, data de modificação).
 *
 * <p>Pendência 2.7: a versão .NET relia e recalculava o hash de todos os PDFs a cada operação.
 * Aqui o arquivo só é relido quando tamanho ou data mudam; se o conteúdo for adulterado
 * mantendo os dois, a conferência completa roda de novo no momento do envio.
 */
public final class FileFingerprintCache {

    private record Key(String path, long size, FileTime modified) {
    }

    private final Map<String, Map.Entry<Key, String>> entries = new ConcurrentHashMap<>();

    public String sha256(Path file) throws IOException {
        var size = Files.size(file);
        var modified = Files.getLastModifiedTime(file);
        var key = new Key(file.toAbsolutePath().normalize().toString(), size, modified);
        var cached = entries.get(key.path());
        if (cached != null && cached.getKey().equals(key)) {
            return cached.getValue();
        }
        var hash = computeSha256(file);
        entries.put(key.path(), Map.entry(key, hash));
        return hash;
    }

    /** Força releitura (usado imediatamente antes do envio). */
    public String sha256Fresh(Path file) throws IOException {
        entries.remove(file.toAbsolutePath().normalize().toString());
        return sha256(file);
    }

    public static String computeSha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[81_920];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
