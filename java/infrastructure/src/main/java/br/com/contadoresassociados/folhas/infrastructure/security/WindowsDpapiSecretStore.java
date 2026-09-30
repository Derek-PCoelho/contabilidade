package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Win32Exception;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * DPAPI (escopo do usuário) com entropia por aplicação (pendência 7.9). Arquivos da versão .NET,
 * gravados sem entropia, são lidos e regravados com entropia na primeira leitura. Arquivo corrompido
 * ou de outro usuário é descartado e o segredo tratado como ausente (pendência 7.10), o que leva a um
 * novo login em vez de uma exceção não tratada.
 */
public final class WindowsDpapiSecretStore implements SecretStore {

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    private static final byte[] ENTROPY = "FolhasDaMichelly.SecretStore.v2".getBytes(StandardCharsets.US_ASCII);

    private final Path directory;

    public WindowsDpapiSecretStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public void store(String key, String value) {
        var clear = value.getBytes(StandardCharsets.UTF_8);
        try {
            write(SecretKeys.fileName(key), Crypt32Util.cryptProtectData(clear, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN,
                    "Folhas da Michelly", null));
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    @Override
    public Optional<String> retrieve(String key) {
        var path = directory.resolve(SecretKeys.fileName(key));
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return Optional.empty();
        }
        byte[] clear = null;
        try {
            try {
                clear = Crypt32Util.cryptUnprotectData(data, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, null);
            } catch (Win32Exception withEntropy) {
                clear = Crypt32Util.cryptUnprotectData(data, null, CRYPTPROTECT_UI_FORBIDDEN, null);
                store(key, new String(clear, StandardCharsets.UTF_8));
            }
            return Optional.of(new String(clear, StandardCharsets.UTF_8));
        } catch (Win32Exception corrupted) {
            remove(key);
            return Optional.empty();
        } finally {
            if (clear != null) {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    @Override
    public void remove(String key) {
        try {
            Files.deleteIfExists(directory.resolve(SecretKeys.fileName(key)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String name, byte[] bytes) {
        try {
            Files.createDirectories(directory);
            var target = directory.resolve(name);
            var temp = directory.resolve(name + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
