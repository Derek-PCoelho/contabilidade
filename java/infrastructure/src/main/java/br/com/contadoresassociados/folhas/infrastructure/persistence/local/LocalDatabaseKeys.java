package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chave do {@code cache.db} (pendências 3.17/7.3): 256 bits aleatórios, guardados no cofre do
 * sistema (DPAPI/Keychain) sob {@link #KEY}. Nunca é derivada de senha nem gravada em arquivo.
 *
 * <p>Se o cofre perdeu a chave mas o banco já está cifrado, os dados locais são irrecuperáveis:
 * o banco é movido para {@code cache.db.unreadable-*} (nada é apagado) e um novo é criado —
 * o catálogo Conectado e a sincronização repovoam o cache; no perfil Local, a restauração do
 * backup {@code .fdmbackup} é o caminho previsto.
 */
public final class LocalDatabaseKeys {

    public static final String KEY = "local-storage/cache-db-key-v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalDatabaseKeys() {
    }

    public record Opened(LocalDatabase database, boolean recoveredFromLostKey, Path quarantinedFile) {
    }

    public static Opened openEncrypted(Path file, SecretStore vault) {
        var existing = vault.retrieve(KEY).filter(k -> !k.isBlank());
        try {
            if (existing.isEmpty() && Files.exists(file) && !LocalDatabase.isPlaintextSqlite(file)
                    && Files.size(file) > 0) {
                var quarantined = quarantine(file);
                var key = createKey(vault);
                return new Opened(LocalDatabase.open(file, key), true, quarantined);
            }
            var key = existing.orElseGet(() -> createKey(vault));
            return new Opened(LocalDatabase.open(file, key), false, null);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Não foi possível preparar o banco local.", e);
        }
    }

    private static String createKey(SecretStore vault) {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        vault.store(KEY, key);
        return key;
    }

    private static Path quarantine(Path file) throws java.io.IOException {
        var target = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
        Files.move(file, target);
        for (var suffix : new String[] {"-wal", "-shm"}) {
            var side = Path.of(file.toAbsolutePath() + suffix);
            if (Files.exists(side)) {
                Files.move(side, Path.of(target.toAbsolutePath() + suffix));
            }
        }
        return target;
    }
}
