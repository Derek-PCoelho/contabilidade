package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.MsalClientException;
import com.microsoft.aad.msal4j.MsalException;
import com.microsoft.aad.msal4j.MsalInteractionRequiredException;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sessão Microsoft 365 via MSAL4J (cliente público, navegador do sistema, loopback). O cache
 * MSAL fica no cofre com a mesma chave e formato do .NET (base64 do cache unificado v3).
 *
 * <p>Pendência 5.4: exceções do MSAL, do cofre e timeouts nunca escapam de
 * {@code status/connect}; {@code accessToken} só lança {@link EmailAccountSessionException}.
 */
public final class MicrosoftGraphEmailAccountSession implements EmailAccountSession {

    public static final String TOKEN_CACHE_KEY = "email-provider/microsoft-graph/msal-v3-cache";

    /** Abstração mínima do MSAL (permite testar sem Azure AD). */
    public interface MsalGateway {
        Optional<Account> firstAccount();

        Account interactive(Set<String> scopes);

        String silent(Set<String> scopes, Account account);

        void removeAll();

        record Account(String username, Object handle) {
        }

        /** O usuário cancelou o login. */
        final class Cancelled extends RuntimeException {
            public Cancelled(Throwable cause) {
                super("authentication_canceled", cause);
            }
        }

        /** É preciso nova interação (token expirado/revogado). */
        final class InteractionRequired extends RuntimeException {
            public InteractionRequired(Throwable cause) {
                super("interaction_required", cause);
            }
        }
    }

    private final MicrosoftGraphOptions options;
    private final SecretStore secretStore;
    private final MsalGateway gateway;

    public MicrosoftGraphEmailAccountSession(MicrosoftGraphOptions options, SecretStore secretStore) {
        this(options, secretStore, options.isConfigured() ? new Msal4jGateway(options, secretStore) : null);
    }

    public MicrosoftGraphEmailAccountSession(MicrosoftGraphOptions options, SecretStore secretStore,
            MsalGateway gateway) {
        this.options = options;
        this.secretStore = secretStore;
        this.gateway = gateway;
    }

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.GRAPH_PROVIDER;
    }

    @Override
    public Status status() {
        if (!options.isConfigured() || gateway == null) {
            return notConfigured();
        }
        try {
            return gateway.firstAccount().map(this::connected).orElseGet(() -> new Status(providerKey(), true, false,
                    null, "Microsoft 365 desconectado", "AUTH_REQUIRED"));
        } catch (RuntimeException e) {
            return failed("AUTH_REQUIRED");
        }
    }

    @Override
    public Status connect() {
        if (!options.isConfigured() || gateway == null) {
            return notConfigured();
        }
        try {
            return connected(gateway.interactive(options.scopes(options.emailSendEnabled())));
        } catch (MsalGateway.Cancelled e) {
            return new Status(providerKey(), true, false, null, "Conexão Microsoft cancelada", "AUTH_CANCELLED");
        } catch (RuntimeException e) {
            return failed("AUTH_REQUIRED");
        }
    }

    @Override
    public void disconnect() {
        try {
            if (gateway != null) {
                gateway.removeAll();
            }
        } catch (RuntimeException ignored) {
            // a sessão local é encerrada mesmo se o MSAL falhar
        } finally {
            secretStore.remove(TOKEN_CACHE_KEY);
        }
    }

    @Override
    public String accessToken(boolean requireSendPermission) {
        if (!options.isConfigured() || gateway == null) {
            throw new EmailAccountSessionException("GRAPH_NOT_CONFIGURED",
                    "Configure ClientId, tenant e destinatário controlado para a Fase 7.");
        }
        if (requireSendPermission && !options.emailSendEnabled()) {
            throw new EmailAccountSessionException("EMAIL_SEND_DISABLED",
                    "O envio pelo Microsoft 365 está desligado nesta instalação.");
        }
        try {
            var account = gateway.firstAccount().orElseThrow(() -> new EmailAccountSessionException("AUTH_REQUIRED",
                    "Conecte uma conta Microsoft 365 de teste."));
            return gateway.silent(options.scopes(requireSendPermission), account);
        } catch (EmailAccountSessionException e) {
            throw e;
        } catch (MsalGateway.InteractionRequired e) {
            throw new EmailAccountSessionException("AUTH_REVOKED",
                    "A autorização Microsoft expirou ou foi revogada; conecte novamente.", e);
        } catch (RuntimeException e) {
            throw new EmailAccountSessionException("AUTH_REQUIRED",
                    "Não foi possível obter autorização Microsoft silenciosamente.", e);
        }
    }

    private Status connected(MsalGateway.Account account) {
        var name = account.username();
        return new Status(providerKey(), true, true, name,
                name == null || name.isBlank() ? "Conta Microsoft 365 conectada" : name, null);
    }

    private Status notConfigured() {
        return new Status(providerKey(), false, false, null, "Microsoft Graph não configurado", "GRAPH_NOT_CONFIGURED");
    }

    private Status failed(String code) {
        return new Status(providerKey(), true, false, null, "Não foi possível conectar a conta Microsoft", code);
    }

    /** Implementação real com MSAL4J; cache persistido no cofre a cada alteração. */
    static final class Msal4jGateway implements MsalGateway {

        private static final long TIMEOUT_SECONDS = 300;

        private final MicrosoftGraphOptions options;
        private final SecretStore secretStore;
        private final ReentrantLock cacheGate = new ReentrantLock();
        private volatile PublicClientApplication application;

        Msal4jGateway(MicrosoftGraphOptions options, SecretStore secretStore) {
            this.options = options;
            this.secretStore = secretStore;
        }

        private PublicClientApplication app() {
            var local = application;
            if (local != null) {
                return local;
            }
            synchronized (this) {
                if (application == null) {
                    try {
                        application = PublicClientApplication.builder(options.clientId().strip())
                                .authority("https://login.microsoftonline.com/"
                                        + ProviderSupport.encode(options.tenantId()) + "/")
                                .setTokenCacheAccessAspect(new CacheAspect())
                                .connectTimeoutForDefaultHttpClient(15_000)
                                .readTimeoutForDefaultHttpClient(30_000)
                                .build();
                    } catch (java.net.MalformedURLException e) {
                        throw new IllegalStateException("Autoridade Microsoft inválida.", e);
                    }
                }
                return application;
            }
        }

        @Override
        public Optional<Account> firstAccount() {
            var accounts = await(app().getAccounts());
            return accounts.stream().findFirst().map(a -> new Account(a.username(), a));
        }

        @Override
        public Account interactive(Set<String> scopes) {
            var parameters = InteractiveRequestParameters.builder(URI.create(options.redirectUri())).scopes(scopes)
                    .build();
            var result = await(app().acquireToken(parameters));
            return new Account(result.account() == null ? null : result.account().username(), result.account());
        }

        @Override
        public String silent(Set<String> scopes, Account account) {
            try {
                return await(app().acquireTokenSilently(SilentParameters.builder(scopes, (IAccount) account.handle())
                        .build())).accessToken();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void removeAll() {
            for (var account : await(app().getAccounts())) {
                await(app().removeAccount(account));
            }
        }

        private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
            try {
                return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MsalGateway.Cancelled(e);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException("O MSAL não respondeu a tempo.", e);
            } catch (ExecutionException | CompletionException e) {
                throw translate(e.getCause() == null ? e : e.getCause());
            }
        }

        private static RuntimeException translate(Throwable cause) {
            if (cause instanceof MsalInteractionRequiredException) {
                return new MsalGateway.InteractionRequired(cause);
            }
            if (cause instanceof MsalClientException client && ("authentication_canceled".equals(client.errorCode())
                    || "user_cancel".equals(client.errorCode()))) {
                return new MsalGateway.Cancelled(cause);
            }
            if (cause instanceof MsalException msal) {
                return msal;
            }
            return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        }

        private final class CacheAspect implements ITokenCacheAccessAspect {
            @Override
            public void beforeCacheAccess(ITokenCacheAccessContext context) {
                cacheGate.lock();
                try {
                    secretStore.retrieve(TOKEN_CACHE_KEY).filter(s -> !s.isBlank()).ifPresent(serialized -> {
                        try {
                            context.tokenCache().deserialize(
                                    new String(Base64.getDecoder().decode(serialized), StandardCharsets.UTF_8));
                        } catch (RuntimeException e) {
                            secretStore.remove(TOKEN_CACHE_KEY);
                        }
                    });
                } finally {
                    cacheGate.unlock();
                }
            }

            @Override
            public void afterCacheAccess(ITokenCacheAccessContext context) {
                if (!context.hasCacheChanged()) {
                    return;
                }
                cacheGate.lock();
                try {
                    var bytes = context.tokenCache().serialize().getBytes(StandardCharsets.UTF_8);
                    try {
                        secretStore.store(TOKEN_CACHE_KEY, Base64.getEncoder().encodeToString(bytes));
                    } finally {
                        java.util.Arrays.fill(bytes, (byte) 0);
                    }
                } finally {
                    cacheGate.unlock();
                }
            }
        }
    }
}
