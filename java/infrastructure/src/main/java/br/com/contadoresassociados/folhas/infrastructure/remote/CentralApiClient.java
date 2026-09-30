package br.com.contadoresassociados.folhas.infrastructure.remote;

import br.com.contadoresassociados.folhas.application.common.SecretStore;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Cliente JSON da API central. Equivale ao {@code HttpClient} + {@code BearerTokenHandler} do
 * .NET: lê o token da sessão no cofre a cada chamada e envia {@code Authorization: Bearer}.
 *
 * <p>Melhorias: exige HTTPS fora de loopback, timeout por requisição (pendência 2.4), sem
 * redirecionamentos (o token nunca vaza para outro host) e cabeçalho {@code X-Client-Version}.
 */
public final class CentralApiClient {

    public static final String ACCESS_TOKEN_KEY = "app-session/access-token";

    public static final class CentralApiException extends RuntimeException {
        private final int status;

        public CentralApiException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        /** Status HTTP ou {@code 0} quando a falha é de rede. */
        public int status() {
            return status;
        }
    }

    public record JsonResponse(int status, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public <T> T read(Class<T> type) {
            if (body == null || body.length == 0) {
                throw new CentralApiException("A API central retornou resposta vazia.", status, null);
            }
            try {
                var value = Json.read(body, type);
                if (value == null) {
                    throw new CentralApiException("A API central retornou resposta vazia.", status, null);
                }
                return value;
            } catch (java.io.UncheckedIOException e) {
                throw new CentralApiException("A API central retornou JSON inválido.", status, e);
            }
        }
    }

    private final HttpClient http;
    private final URI baseAddress;
    private final SecretStore secretStore;
    private final Duration timeout;
    private final String clientVersion;

    public CentralApiClient(HttpClient http, URI baseAddress, SecretStore secretStore, Duration timeout,
            String clientVersion) {
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build() : http;
        this.baseAddress = requireSecure(normalize(baseAddress));
        this.secretStore = secretStore;
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        this.clientVersion = clientVersion;
    }

    public URI baseAddress() {
        return baseAddress;
    }

    public JsonResponse get(String path) {
        return send(builder(path).GET());
    }

    public JsonResponse postJson(String path, Object body) {
        return send(builder(path).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body))));
    }

    private HttpRequest.Builder builder(String path) {
        var relative = path.startsWith("/") ? path.substring(1) : path;
        var builder = HttpRequest.newBuilder(baseAddress.resolve(relative)).timeout(timeout)
                .header("Accept", "application/json");
        if (clientVersion != null) {
            builder.header("X-Client-Version", clientVersion);
        }
        token().ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return builder;
    }

    private Optional<String> token() {
        if (secretStore == null) {
            return Optional.empty();
        }
        try {
            return secretStore.retrieve(ACCESS_TOKEN_KEY).filter(t -> !t.isBlank());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private JsonResponse send(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new JsonResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new CentralApiException("A API central não respondeu.", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CentralApiException("A chamada à API central foi interrompida.", 0, e);
        }
    }

    private static URI normalize(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Endereço da API central ausente.");
        }
        var text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    /** HTTPS obrigatório, exceto em loopback (desenvolvimento). */
    static URI requireSecure(URI uri) {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        var loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("A API central exige HTTPS.");
        }
        return uri;
    }
}
