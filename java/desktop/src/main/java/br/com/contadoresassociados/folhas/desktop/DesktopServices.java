package br.com.contadoresassociados.folhas.desktop;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.infrastructure.clients.SqliteLocalClientCatalogService;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabaseKeys;
import br.com.contadoresassociados.folhas.infrastructure.security.NativeSecretStores;
import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.documents.DocumentPeriodParser;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewContext;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewOptions;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewService;
import br.com.contadoresassociados.folhas.application.documents.ValidationRules;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentRecognitionOptions;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentRecognitionService;
import br.com.contadoresassociados.folhas.infrastructure.documents.DeterministicDocumentClassifier;
import br.com.contadoresassociados.folhas.infrastructure.documents.PdfBoxTextExtractor;
import br.com.contadoresassociados.folhas.infrastructure.documents.ProfileDocumentParser;
import br.com.contadoresassociados.folhas.infrastructure.documents.SqliteLocalClientResolver;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteDocumentReviewStore;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteRecognitionCache;
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
    private final DocumentReviewService review;
    private final DocumentRecognitionService recognition;
    private final String startupNotice;

    /** Perfil Local: mesmo escopo e operador da versão .NET ({@code LocalDesktopOperationContextAccessor}). */
    public static final String LOCAL_SCOPE_KEY = "unauthenticated-local";
    public static final String LOCAL_ACTOR_ID = "unauthenticated-operator";

    private DesktopServices(Path dataDirectory, Clock clock, SecretStore vault, LocalDatabaseKeys.Opened opened) {
        this.dataDirectory = dataDirectory;
        this.clock = clock;
        this.vault = vault;
        this.database = opened.database();
        var reviewOptions = DocumentReviewOptions.defaults();
        var resolver = new SqliteLocalClientResolver(database);
        this.review = new DocumentReviewService(new SqliteDocumentReviewStore(database),
                () -> new DocumentReviewContext(LOCAL_SCOPE_KEY, LOCAL_ACTOR_ID, "Operador local"),
                new DocumentPeriodParser(), ValidationRules.defaults(reviewOptions), reviewOptions, clock, resolver);
        this.recognition = new DocumentRecognitionService(new PdfBoxTextExtractor(), new DeterministicDocumentClassifier(),
                new ProfileDocumentParser(), resolver, new SqliteRecognitionCache(database, clock),
                DocumentRecognitionOptions.defaults());
        // Cadastro e revisão ficam no mesmo SQLite: a revalidação roda na transação da alteração
        // cadastral (tudo ou nada), como na versão .NET.
        this.catalog = new SqliteLocalClientCatalogService(database, clock,
                () -> review.revalidate(CancellationToken.NONE));
        this.startupNotice = opened.recoveredFromLostKey()
                ? "A chave do banco local não estava no cofre. O arquivo anterior foi preservado em "
                        + opened.quarantinedFile().getFileName() + " e um novo banco foi criado."
                : null;
    }

    public static DesktopServices create(Path dataDirectory) {
        return create(dataDirectory, DesktopEnvironment.allowInMemoryVault());
    }

    public static DesktopServices create(Path dataDirectory, boolean allowInMemoryVault) {
        var clock = Clock.system();
        var vault = NativeSecretStores.forCurrentPlatform(dataDirectory, allowInMemoryVault);
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

    public DocumentReviewService review() {
        return review;
    }

    public DocumentRecognitionService recognition() {
        return recognition;
    }

    /** Acervo organizado por ano/mês (mesmo local padrão da versão .NET). */
    public Path documentArchiveDirectory() {
        return dataDirectory.resolve("Documentos");
    }

    public String startupNotice() {
        return startupNotice;
    }

    @Override
    public void close() {
        database.close();
    }
}
