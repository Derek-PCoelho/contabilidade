package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import java.nio.file.Path;
import java.util.Locale;

/** Seleciona o cofre do sistema operacional (mesmos locais da versão .NET). */
public final class NativeSecretStores {

    private NativeSecretStores() {
    }

    public static SecretStore forCurrentPlatform(Path dataDirectory, boolean allowInMemoryFallback) {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacOsKeychainSecretStore();
        }
        if (os.contains("win")) {
            return new WindowsDpapiSecretStore(dataDirectory.resolve("SecureStore"));
        }
        if (allowInMemoryFallback) {
            return new InMemorySecretStore();
        }
        throw new UnsupportedOperationException("O cofre persistente é suportado somente no macOS e Windows.");
    }
}
