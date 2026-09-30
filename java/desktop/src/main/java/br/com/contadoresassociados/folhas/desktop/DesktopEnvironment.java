package br.com.contadoresassociados.folhas.desktop;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Local dos dados do Desktop — o mesmo diretório usado pela versão .NET
 * ({@code Environment.SpecialFolder.LocalApplicationData/FolhasDaMichelly}), para o Java abrir
 * o {@code cache.db} existente sem migração manual.
 */
public final class DesktopEnvironment {

    public static final String APP_FOLDER = "FolhasDaMichelly";

    private DesktopEnvironment() {
    }

    public static Path dataDirectory() {
        var override = env("FOLHAS_DESKTOP_DATA_DIR");
        if (override.isPresent()) {
            return Path.of(override.get());
        }
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            return env("LOCALAPPDATA").map(Path::of).orElse(home.resolve("AppData/Local")).resolve(APP_FOLDER);
        }
        if (os.contains("mac")) {
            return home.resolve("Library/Application Support").resolve(APP_FOLDER);
        }
        return env("XDG_DATA_HOME").map(Path::of).orElse(home.resolve(".local/share")).resolve(APP_FOLDER);
    }

    /** Endereço do Server central; vazio = perfil Local (somente SQLite). */
    public static Optional<String> apiBaseAddress() {
        return env("FOLHAS_API_BASE_ADDRESS");
    }

    /** Somente para pré-visualização/desenvolvimento em Linux: cofre em memória. */
    public static boolean allowInMemoryVault() {
        return env("FOLHAS_DESKTOP_ALLOW_MEMORY_VAULT").map("true"::equalsIgnoreCase).orElse(false);
    }

    static Optional<String> env(String name) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
