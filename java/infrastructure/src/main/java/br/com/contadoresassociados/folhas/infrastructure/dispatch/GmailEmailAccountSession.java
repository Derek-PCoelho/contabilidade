package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Sessão OAuth do Gmail (PKCE S256 + loopback). O token fica no cofre com a mesma chave e o
 * mesmo JSON do .NET, então uma conta já conectada continua conectada após a migração.
 *
 * <p>Correções: 5.2 ({@code client_secret} na troca e no refresh, lido do cofre); 5.4 (qualquer
 * falha inesperada — JSON sem campo, cofre indisponível, timeout — vira código estável em vez
 * de exceção solta).
 */
public final class GmailEmailAccountSession implements EmailAccountSession {

    public static final String TOKEN_CACHE_KEY = "email-provider/google-gmail/oauth-v1-token";

    record StoredToken(String accessToken, String refreshToken, OffsetDateTime expiresAtUtc, String scope,
            String accountEmail) {

        StoredToken withAccount(String email) {
            return new StoredToken(accessToken, refreshToken, expiresAtUtc, scope, email);
        }
    }

    private final ProviderHttp http;
    private final GmailOptions options;
    private final SecretStore secretStore;
    private final Clock clock;
    private final OAuthAuthorizationReceiver receiver;
    private final ReentrantLock tokenGate = new ReentrantLock();

    public GmailEmailAccountSession(HttpClient httpClient, GmailOptions options, SecretStore secretStore, Clock clock,
            OAuthAuthorizationReceiver receiver) {
        this.http = new ProviderHttp(httpClient == null ? ProviderHttp.defaultClient() : httpClient, 0,
                ProviderHttp.Sleeper.NONE, Duration.ofSeconds(30));
        this.options = options;
        this.secretStore = secretStore;
        this.clock = clock;
        this.receiver = receiver;
    }

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.GMAIL_PROVIDER;
    }

    @Override
    public Status status() {
        if (!options.isConfigured()) {
            return notConfigured();
        }
        try {
            return readToken().map(t -> connected(t.accountEmail())).orElseGet(this::disconnected);
        } catch (RuntimeException e) {
            return failed("SECRET_STORE_UNAVAILABLE");
        }
    }

    @Override
    public Status connect() {
        return connect(CancellationToken.NONE);
    }

    public Status connect(CancellationToken cancellationToken) {
        if (!options.isConfigured()) {
            return notConfigured();
        }
        var state = ProviderSupport.randomBase64Url(32);
        var verifier = ProviderSupport.randomBase64Url(64);
        var challenge = ProviderSupport.base64Url(ProviderSupport.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        try {
            var authorization = receiver.receive(redirect -> authorizationUri(redirect, state, challenge), state,
                    cancellationToken);
            if (authorization.error() != null && !authorization.error().isBlank()) {
                return "access_denied".equals(authorization.error()) ? cancelled() : failed("AUTH_REQUIRED");
            }
            var redirect = authorization.redirectUri();
            if (!state.equals(authorization.state()) || authorization.code() == null || authorization.code().isBlank()
                    || redirect == null || !"http".equals(redirect.getScheme()) || !isLoopback(redirect)) {
                return failed("AUTH_STATE_INVALID");
            }
            var token = exchange(authorization.code(), redirect, verifier);
            var email = profileEmail(token.accessToken());
            token = token.withAccount(email);
            writeToken(token);
            return connected(email);
        } catch (OperationCancelledException e) {
            return cancelled();
        } catch (EmailAccountSessionException e) {
            return failed(e.code());
        } catch (RuntimeException e) {
            // pendência 5.4: rede, JSON, cofre — nunca derrubar a tela de conexão
            return failed("AUTH_REQUIRED");
        }
    }

    @Override
    public void disconnect() {
        try {
            var token = readToken();
            if (token.isPresent()) {
                var t = token.get();
                var value = t.refreshToken() == null || t.refreshToken().isBlank() ? t.accessToken() : t.refreshToken();
                http.send(() -> form(options.revocationEndpoint(), Map.of("token", value)), false, null);
            }
        } catch (RuntimeException ignored) {
            // A sessão local é encerrada mesmo se o provedor estiver indisponível.
        } finally {
            secretStore.remove(TOKEN_CACHE_KEY);
        }
    }

    @Override
    public String accessToken(boolean requireSendPermission) {
        if (!options.isConfigured()) {
            throw new EmailAccountSessionException("GMAIL_NOT_CONFIGURED",
                    "Configure o cliente OAuth Google e o destinatário controlado da Fase 8.");
        }
        if (requireSendPermission && !options.emailSendEnabled()) {
            throw new EmailAccountSessionException("EMAIL_SEND_DISABLED",
                    "O envio pelo Gmail está desligado nesta instalação.");
        }
        tokenGate.lock();
        try {
            var token = readToken().orElseThrow(() -> new EmailAccountSessionException("AUTH_REQUIRED",
                    "Conecte uma conta Google dedicada antes de continuar."));
            if (token.expiresAtUtc() != null && token.expiresAtUtc().isAfter(clock.nowUtc().plusMinutes(2))) {
                return token.accessToken();
            }
            return refresh(token);
        } catch (ProviderHttp.TransportException e) {
            throw new EmailAccountSessionException("AUTH_TEMPORARILY_UNAVAILABLE",
                    "Não foi possível renovar a autorização Google.", e);
        } catch (ProviderHttp.InvalidResponseException e) {
            throw new EmailAccountSessionException("AUTH_TEMPORARILY_UNAVAILABLE",
                    "O Google devolveu uma resposta inesperada ao renovar a autorização.", e);
        } finally {
            tokenGate.unlock();
        }
    }

    /** Pendência 5.2: guarda o segredo do cliente Desktop no cofre (nunca no arquivo de configuração). */
    public void storeClientSecret(String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()) {
            secretStore.remove(GmailOptions.CLIENT_SECRET_KEY);
        } else {
            secretStore.store(GmailOptions.CLIENT_SECRET_KEY, clientSecret.strip());
        }
    }

    URI authorizationUri(URI redirect, String state, String challenge) {
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", options.clientId().strip());
        params.put("redirect_uri", redirect.toString());
        params.put("response_type", "code");
        params.put("scope", String.join(" ", options.scopes()));
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        return URI.create(options.authorizationEndpoint() + "?" + query(params));
    }

    private StoredToken exchange(String code, URI redirect, String verifier) {
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", options.clientId().strip());
        clientSecret().ifPresent(s -> params.put("client_secret", s));
        params.put("code", code);
        params.put("code_verifier", verifier);
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", redirect.toString());
        var response = http.send(() -> form(options.tokenEndpoint(), params), false, null);
        if (!response.ok()) {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "O Google não autorizou esta conexão.");
        }
        var root = response.json();
        var access = ProviderHttp.requiredText(root, "access_token");
        var refreshToken = ProviderHttp.requiredText(root, "refresh_token");
        var expiresIn = root.path("expires_in").asInt(0);
        var scopes = root.hasNonNull("scope") ? root.get("scope").asText() : String.join(" ", options.scopes());
        if (Arrays.stream(scopes.split(" ")).noneMatch(GmailOptions.COMPOSE_SCOPE::equals)) {
            throw new EmailAccountSessionException("AUTH_SCOPE_MISSING",
                    "O consentimento não incluiu a permissão Gmail necessária.");
        }
        return new StoredToken(access, refreshToken, clock.nowUtc().plusSeconds(Math.max(1, expiresIn)), scopes, "");
    }

    private String refresh(StoredToken token) {
        var params = new LinkedHashMap<String, String>();
        params.put("client_id", options.clientId().strip());
        clientSecret().ifPresent(s -> params.put("client_secret", s));
        params.put("refresh_token", token.refreshToken() == null ? "" : token.refreshToken());
        params.put("grant_type", "refresh_token");
        var response = http.send(() -> form(options.tokenEndpoint(), params), false, null);
        if (response.status() == 400 || response.status() == 401) {
            secretStore.remove(TOKEN_CACHE_KEY);
            throw new EmailAccountSessionException("AUTH_REVOKED",
                    "A autorização Google expirou ou foi revogada; conecte novamente.");
        }
        if (!response.ok()) {
            throw new EmailAccountSessionException(ProviderHttp.isTransient(response.status())
                    ? "AUTH_TEMPORARILY_UNAVAILABLE" : "AUTH_REQUIRED", "Não foi possível renovar a autorização Google.");
        }
        var root = response.json();
        var updated = new StoredToken(ProviderHttp.requiredText(root, "access_token"), token.refreshToken(),
                clock.nowUtc().plusSeconds(Math.max(1, root.path("expires_in").asInt(0))), token.scope(),
                token.accountEmail());
        writeToken(updated);
        return updated.accessToken();
    }

    private String profileEmail(String accessToken) {
        var response = http.send(() -> HttpRequest.newBuilder(options.apiBaseAddress().resolve("users/me/profile")).GET(),
                false, () -> accessToken);
        if (!response.ok()) {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "Não foi possível confirmar a conta Gmail conectada.");
        }
        return ProviderHttp.requiredText(response.json(), "emailAddress");
    }

    private Optional<String> clientSecret() {
        if (options.clientSecret() != null && !options.clientSecret().isBlank()) {
            return Optional.of(options.clientSecret().strip());
        }
        return secretStore.retrieve(GmailOptions.CLIENT_SECRET_KEY).filter(s -> !s.isBlank()).map(String::strip);
    }

    Optional<StoredToken> readToken() {
        var serialized = secretStore.retrieve(TOKEN_CACHE_KEY).orElse(null);
        if (serialized == null || serialized.isBlank()) {
            return Optional.empty();
        }
        try {
            var token = Json.read(serialized, StoredToken.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                secretStore.remove(TOKEN_CACHE_KEY);
                return Optional.empty();
            }
            return Optional.of(token);
        } catch (RuntimeException e) {
            secretStore.remove(TOKEN_CACHE_KEY);
            return Optional.empty();
        }
    }

    private void writeToken(StoredToken token) {
        secretStore.store(TOKEN_CACHE_KEY, Json.write(token));
    }

    private static HttpRequest.Builder form(URI endpoint, Map<String, String> params) {
        return HttpRequest.newBuilder(endpoint).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(query(params), StandardCharsets.UTF_8));
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> ProviderSupport.encode(e.getKey()) + "=" + ProviderSupport.encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static boolean isLoopback(URI uri) {
        var host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host);
    }

    private Status notConfigured() {
        return new Status(providerKey(), false, false, null, "Google não configurado", "GMAIL_NOT_CONFIGURED");
    }

    private Status disconnected() {
        return new Status(providerKey(), true, false, null, "Google desconectado", "AUTH_REQUIRED");
    }

    private Status connected(String email) {
        return new Status(providerKey(), true, true, email, email, null);
    }

    private Status cancelled() {
        return new Status(providerKey(), true, false, null, "Conexão Google cancelada", "AUTH_CANCELLED");
    }

    private Status failed(String code) {
        return new Status(providerKey(), true, false, null, "Não foi possível conectar a conta Google", code);
    }
}
