package br.com.contadoresassociados.folhas.desktop;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.infrastructure.clients.SqliteLocalClientCatalogService;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabaseKeys;
import br.com.contadoresassociados.folhas.infrastructure.security.NativeSecretStores;
import java.nio.file.Path;

/**
 * Composição dos serviços do Desktop (equivalente ao {@code AddDesktopPhase2} da versão .NET).
 * O {@code cache.db} é aberto cifrado com a chave do cofre do sistema (pendências 3.17/7.3).
 */
public final class DesktopServices implements AutoCloseable {

    private final Path dataDirectory;
    private final Clock clock;
    private final SecretStore vault;
    private final LocalDatabase database;
    private final ClientCatalogService catalog;
    private final String startupNotice;

    private DesktopServices(Path dataDirectory, Clock clock, SecretStore vault, LocalDatabaseKeys.Opened opened) {
        this.dataDirectory = dataDirectory;
        this.clock = clock;
        this.vault = vault;
        this.database = opened.database();
        this.catalog = new SqliteLocalClientCatalogService(database, clock, null);
        this.startupNotice = opened.recoveredFromLostKey()
                ? "A chave do banco local não estava no cofre. O arquivo anterior foi preservado em "
                        + opened.quarantinedFile().getFileName() + " e um novo banco foi criado."
                : null;
    }

    public static DesktopServices create(Path dataDirectory) {
        var clock = Clock.system();
        var vault = NativeSecretStores.forCurrentPlatform(dataDirectory, DesktopEnvironment.allowInMemoryVault());
        var opened = LocalDatabaseKeys.openEncrypted(dataDirectory.resolve("cache.db"), vault);
        return new DesktopServices(dataDirectory, clock, vault, opened);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Clock clock() {
        return clock;
    }

    public SecretStore vault() {
        return vault;
    }

    public ClientCatalogService catalog() {
        return catalog;
    }

    public String startupNotice() {
        return startupNotice;
    }

    @Override
    public void close() {
        database.close();
    }
}
