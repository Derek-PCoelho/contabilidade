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
import br.com.contadoresassociados.folhas.application.dispatch.DeterministicDispatchMessageComposer;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchExecutionContext;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowException;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowService;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountConnectionService;
import br.com.contadoresassociados.folhas.application.dispatch.RemoteEmailSendGuard;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import br.com.contadoresassociados.folhas.infrastructure.dispatch.FakeEmailAccountSession;
import br.com.contadoresassociados.folhas.infrastructure.dispatch.FakeEmailProvider;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.SqliteDispatchWorkflowStore;
import br.com.contadoresassociados.folhas.infrastructure.reports.StandardDispatchReportExporter;
import br.com.contadoresassociados.folhas.infrastructure.security.DefaultSensitiveTextRedactor;
import java.nio.file.Path;
import java.util.Set;

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
    private final DispatchWorkflowService dispatch;
    private final EmailAccountConnectionService emailConnection;
    private final String startupNotice;

    /**
     * Permissões do perfil Local — as mesmas do {@code LocalDesktopOperationContextAccessor} (.NET):
     * sem {@code email.send}, o envio real exige o perfil Conectado.
     */
    public static final Set<AppPermission> LOCAL_PERMISSIONS = Set.of(AppPermission.DOCUMENTS_PROCESS,
            AppPermission.BATCH_APPROVE, AppPermission.EMAIL_DRAFT, AppPermission.AUDIT_EXPORT);

    /**
     * Sem servidor central não há quem autorize operações externas: o preflight falha fechado
     * (pendência 2.4). O modo local seguro não passa por aqui.
     */
    private static final RemoteEmailSendGuard LOCAL_GUARD = request -> {
        throw new DispatchWorkflowException("REMOTE_SEND_GUARD_UNAVAILABLE",
                "O controle central não está disponível no perfil local.");
    };

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
        var dispatchOptions = DispatchWorkflowOptions.defaults();
        var dispatchStore = new SqliteDispatchWorkflowStore(database);
        java.util.function.Supplier<DispatchExecutionContext> context = () -> new DispatchExecutionContext(LOCAL_SCOPE_KEY,
                LOCAL_ACTOR_ID, "Operador local", LOCAL_PERMISSIONS);
        this.dispatch = new DispatchWorkflowService(dispatchStore, review,
                new DeterministicDispatchMessageComposer(catalog, clock, dispatchOptions),
                new FakeEmailProvider(new FakeEmailProvider.Options(dataDirectory.resolve("FakeOutbox"), null, null), clock),
                context::get, LOCAL_GUARD, new StandardDispatchReportExporter(clock, Clock.BRAZIL), clock, dispatchOptions,
                new DefaultSensitiveTextRedactor());
        this.emailConnection = new EmailAccountConnectionService(new FakeEmailAccountSession(), dispatchStore, context::get,
                clock);
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

    public DispatchWorkflowService dispatch() {
        return dispatch;
    }

    public EmailAccountConnectionService emailConnection() {
        return emailConnection;
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
