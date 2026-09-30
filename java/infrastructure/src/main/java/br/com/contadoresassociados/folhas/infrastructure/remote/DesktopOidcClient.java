package br.com.contadoresassociados.folhas.infrastructure.remote;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Login do Desktop no Server central (pendência 6.1 — não existia na versão .NET).
 *
 * <ul>
 *   <li>Authorization Code + PKCE S256, cliente público {@code folhas-desktop};</li>
 *   <li>retorno por loopback {@code http://127.0.0.1:{porta}/} (RFC 8252), com {@code state} conferido;</li>
 *   <li>tokens guardados somente no cofre do sistema ({@link SecretStore});</li>
 *   <li>renovação por refresh token rotativo e revogação no logout (encerra a sessão do dispositivo);</li>
 *   <li>o identificador do dispositivo é estável e fica no cofre, para o servidor listar/encerrar sessões.</li>
 * </ul>
 *
 * <p>As claims do token são lidas apenas para apresentação e escopo local; a autorização real
 * continua sendo feita pelo servidor em cada chamada (ele valida a assinatura).
 */
public final class DesktopOidcClient {

    public static final String CLIENT_ID = "folhas-desktop";
    public static final String ACCESS_TOKEN_KEY = CentralApiClient.ACCESS_TOKEN_KEY;
    public static final String REFRESH_TOKEN_KEY = "app-session/refresh-token";
    public static final String DEVICE_ID_KEY = "app-session/device-id";
    static final String SCOPES = "openid offline_access email profile roles folhas_api";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    /** Sessão atual derivada do access token. */
    public record Session(UUID userId, UUID organizationId, UUID deviceSessionId, String email, String displayName,
            List<String> roles, Set<AppPermission> permissions, boolean multiFactor, Instant expiresAt) {

        public Session {
            roles = List.copyOf(roles);
            permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(permissions);
        }

        public boolean expired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        /** Escopo de dados local por organização (mesmo formato do .NET: GUID sem hífens). */
        public String scopeKey() {
            return organizationId.toString().replace("-", "");
        }
    }

    public static final class LoginException extends RuntimeException {
        private final String code;

        public LoginException(String code, String message) {
            super(message);
            this.code = code;
        }

        public LoginException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final URI authority;
    private final SecretStore vault;
    private final HttpClient http;
    private final Consumer<URI> browser;
    private final Duration loginTimeout;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DesktopOidcClient(URI authority, SecretStore vault, HttpClient http, Consumer<URI> browser, Duration loginTimeout,
            Clock clock) {
        this.authority = CentralApiClient.requireSecure(normalize(Objects.requireNonNull(authority, "authority")));
        this.vault = Objects.requireNonNull(vault, "vault");
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build() : http;
        this.browser = browser == null ? DesktopOidcClient::openBrowser : browser;
        this.loginTimeout = loginTimeout == null ? Duration.ofMinutes(5) : loginTimeout;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public URI authority() {
        return authority;
    }

    // ================================================================== sessão

    /** Sessão guardada no cofre (sem chamar a rede). Vazia se não há token ou ele é ilegível. */
    public Optional<Session> current() {
        return retrieve(ACCESS_TOKEN_KEY).flatMap(DesktopOidcClient::parse);
    }

    /** Sessão válida: renova se o access token estiver perto de expirar. */
    public Optional<Session> ensureFresh() {
        var session = current();
        if (session.isEmpty()) {
            return session;
        }
        var expires = session.get().expiresAt();
        if (expires != null && clock.instant().plus(REFRESH_MARGIN).isAfter(expires)) {
            try {
                return Optional.of(refresh());
            } catch (LoginException e) {
                return session.get().expired(clock.instant()) ? Optional.empty() : session;
            }
        }
        return session;
    }

    // ================================================================== login

    /** Abre o navegador do sistema e aguarda o retorno do servidor no loopback. */
    public Session login(String deviceName, CancellationToken cancellation) {
        var verifier = randomToken(48);
        var challenge = s256(verifier);
        var state = randomToken(24);
        var nonce = randomToken(24);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new LoginException("LOOPBACK_UNAVAILABLE", "Não foi possível abrir a porta local de retorno do login.", e);
        }
        var redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var future = new CompletableFuture<Map<String, String>>();
        server.createContext("/", exchange -> {
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            var query = parseQuery(exchange.getRequestURI().getRawQuery());
            var ok = query.get("error") == null && query.get("code") != null && state.equals(query.get("state"));
            var bytes = page(ok, query.get("error_description")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            future.complete(query);
        });
        server.start();
        Map<String, String> result;
        try {
            var params = new LinkedHashMap<String, String>();
            params.put("client_id", CLIENT_ID);
            params.put("redirect_uri", redirect);
            params.put("response_type", "code");
            params.put("scope", SCOPES);
            params.put("state", state);
            params.put("nonce", nonce);
            params.put("code_challenge", challenge);
            params.put("code_challenge_method", "S256");
            params.put("device_id", deviceId().toString());
            params.put("device_name", deviceName == null || deviceName.isBlank() ? "Computador do escritório" : deviceName);
            browser.accept(URI.create(authority.resolve("connect/authorize") + "?" + form(params)));
            result = await(future, cancellation);
        } finally {
            server.stop(0);
        }
        if (result.get("error") != null) {
            throw new LoginException("LOGIN_DENIED", result.get("error_description") != null
                    ? result.get("error_description") : "O login não foi autorizado.");
        }
        if (!state.equals(result.get("state")) || result.get("code") == null) {
            throw new LoginException("LOGIN_STATE_MISMATCH", "O retorno do login não corresponde a esta solicitação.");
        }
        if (result.get("iss") != null && !sameIssuer(normalize(URI.create(result.get("iss"))), authority)) {
            throw new LoginException("LOGIN_ISSUER_MISMATCH", "O retorno do login veio de um servidor diferente do configurado.");
        }
        var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", CLIENT_ID);
        body.put("code", result.get("code"));
        body.put("redirect_uri", redirect);
        body.put("code_verifier", verifier);
        return storeTokens(postToken(body));
    }

    private Map<String, String> await(CompletableFuture<Map<String, String>> future, CancellationToken cancellation) {
        var deadline = System.nanoTime() + loginTimeout.toNanos();
        while (true) {
            if (cancellation != null && cancellation.isCancellationRequested()) {
                throw new OperationCancelledException("O login foi cancelado.");
            }
            try {
                return future.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (System.nanoTime() > deadline) {
                    throw new LoginException("LOGIN_TIMEOUT", "O tempo para concluir o login expirou. Tente novamente.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCancelledException("O login foi interrompido.");
            } catch (ExecutionException e) {
                throw new LoginException("LOGIN_CALLBACK_FAILED", "Falha ao receber o retorno do login.", e.getCause());
            }
        }
    }

    // ================================================================== renovação e saída

    public Session refresh() {
        var refreshToken = retrieve(REFRESH_TOKEN_KEY)
                .orElseThrow(() -> new LoginException("SESSION_EXPIRED", "A sessão expirou. Entre novamente."));
        var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "refresh_token");
        body.put("client_id", CLIENT_ID);
        body.put("refresh_token", refreshToken);
        try {
            return storeTokens(postToken(body));
        } catch (LoginException e) {
            if (!"SERVER_UNAVAILABLE".equals(e.code())) {
                // Token rejeitado (reuso, revogação, conta bloqueada): a sessão local deixa de valer.
                clearTokens();
            }
            throw e;
        }
    }

    /** Revoga no servidor (encerra a sessão do dispositivo) e apaga os tokens do cofre, mesmo offline. */
    public void logout() {
        var refreshToken = retrieve(REFRESH_TOKEN_KEY);
        try {
            if (refreshToken.isPresent()) {
                var body = new LinkedHashMap<String, String>();
                body.put("token", refreshToken.get());
                body.put("client_id", CLIENT_ID);
                http.send(formRequest("connect/revoke", body), HttpResponse.BodyHandlers.discarding());
            }
        } catch (IOException e) {
            // Sem rede: o token local é apagado; o servidor expira a sessão pelo prazo.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clearTokens();
        }
    }

    private void clearTokens() {
        vault.remove(ACCESS_TOKEN_KEY);
        vault.remove(REFRESH_TOKEN_KEY);
    }

    // ================================================================== token endpoint

    private JsonNode postToken(Map<String, String> body) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(formRequest("connect/token", body), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new LoginException("SERVER_UNAVAILABLE", "O servidor do escritório não respondeu. Verifique a conexão.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCancelledException("O login foi interrompido.");
        }
        JsonNode json;
        try {
            json = Json.mapper().readTree(response.body());
        } catch (IOException e) {
            throw new LoginException("TOKEN_RESPONSE_INVALID", "O servidor devolveu uma resposta inválida ao login.", e);
        }
        if (response.statusCode() != 200) {
            var description = json == null ? null : json.path("error_description").asText(null);
            throw new LoginException(json == null ? "TOKEN_ERROR" : json.path("error").asText("TOKEN_ERROR"),
                    description == null || description.isBlank() ? "O servidor recusou o login." : description);
        }
        if (json == null || !json.hasNonNull("access_token")) {
            throw new LoginException("TOKEN_RESPONSE_INVALID", "O servidor devolveu uma resposta inválida ao login.");
        }
        return json;
    }

    private Session storeTokens(JsonNode json) {
        var access = json.get("access_token").asText();
        var session = parse(access).orElseThrow(() -> new LoginException("TOKEN_RESPONSE_INVALID",
                "O token recebido não identifica a organização e o usuário."));
        vault.store(ACCESS_TOKEN_KEY, access);
        if (json.hasNonNull("refresh_token")) {
            vault.store(REFRESH_TOKEN_KEY, json.get("refresh_token").asText());
        }
        return session;
    }

    private HttpRequest formRequest(String path, Map<String, String> body) {
        return HttpRequest.newBuilder(authority.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body))).build();
    }

    // ================================================================== auxiliares

    /** Lê as claims do JWT (sem validar assinatura — o servidor valida em cada chamada). */
    static Optional<Session> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            var payload = Json.mapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
            var sub = UUID.fromString(payload.path("sub").asText());
            var org = UUID.fromString(payload.path("organization_id").asText());
            var device = payload.hasNonNull("device_session_id") ? UUID.fromString(payload.get("device_session_id").asText())
                    : new UUID(0, 0);
            var permissions = EnumSet.noneOf(AppPermission.class);
            strings(payload.get("permission")).forEach(p -> AppPermission.fromWireName(p).ifPresent(permissions::add));
            var exp = payload.hasNonNull("exp") ? Instant.ofEpochSecond(payload.get("exp").asLong()) : null;
            var name = payload.path("name").asText(payload.path("email").asText(""));
            return Optional.of(new Session(sub, org, device, payload.path("email").asText(""), name,
                    strings(payload.get("role")), permissions, strings(payload.get("amr")).contains("mfa"), exp));
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    private static List<String> strings(JsonNode node) {
        var list = new ArrayList<String>();
        if (node == null || node.isNull()) {
            return list;
        }
        if (node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        } else {
            list.add(node.asText());
        }
        return list;
    }

    private Optional<String> retrieve(String key) {
        try {
            return vault.retrieve(key).filter(v -> !v.isBlank());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    UUID deviceId() {
        var existing = retrieve(DEVICE_ID_KEY);
        if (existing.isPresent()) {
            try {
                return UUID.fromString(existing.get());
            } catch (IllegalArgumentException ignored) {
                // valor corrompido: gera outro
            }
        }
        var id = UUID.randomUUID();
        vault.store(DEVICE_ID_KEY, id.toString());
        return id;
    }

    private String randomToken(int bytes) {
        var data = new byte[bytes];
        random.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static String s256(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    static Map<String, String> parseQuery(String raw) {
        var map = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (var pair : raw.split("&")) {
            var idx = pair.indexOf('=');
            var key = URLDecoder.decode(idx < 0 ? pair : pair.substring(0, idx), StandardCharsets.UTF_8);
            var value = idx < 0 ? "" : URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.putIfAbsent(key, value);
        }
        return map;
    }

    /** Mesmo emissor; {@code localhost} e {@code 127.0.0.1} na mesma porta são equivalentes (loopback). */
    static boolean sameIssuer(URI a, URI b) {
        if (a.equals(b)) {
            return true;
        }
        return loopback(a.getHost()) && loopback(b.getHost()) && a.getPort() == b.getPort()
                && java.util.Objects.equals(a.getScheme(), b.getScheme()) && java.util.Objects.equals(a.getPath(), b.getPath());
    }

    private static boolean loopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    private static URI normalize(URI uri) {
        var text = uri.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    static String page(boolean success, String detail) {
        var title = success ? "Login concluído" : "Login não concluído";
        var text = success ? "Você pode fechar esta janela e voltar ao Folhas da Michelly."
                : "Volte ao Folhas da Michelly para tentar novamente.";
        return "<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>" + title + "</title>"
                + "<body style=\"font-family:system-ui;padding:48px;color:#24221e\"><h1>" + title + "</h1><p>" + text
                + "</p></body></html>";
    }

    private static void openBrowser(URI uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return;
            }
            var os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            var command = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", uri.toString()}
                    : os.contains("mac") ? new String[] {"open", uri.toString()} : new String[] {"xdg-open", uri.toString()};
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new LoginException("BROWSER_UNAVAILABLE", "Não foi possível abrir o navegador do sistema.", e);
        }
    }
}
