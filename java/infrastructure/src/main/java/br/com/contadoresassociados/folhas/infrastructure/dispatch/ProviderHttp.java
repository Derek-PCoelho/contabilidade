package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cliente HTTP comum aos provedores Gmail e Microsoft Graph: bearer por tentativa, novas
 * tentativas somente em chamadas seguras (GET/consultas), respeito ao {@code Retry-After} e
 * backoff exponencial com jitter (500·2ⁿ ms, máximo 30 s) — o mesmo comportamento do .NET.
 */
final class ProviderHttp {

    /** Espera injetável para testes (sem atrasos reais). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        Sleeper REAL = d -> Thread.sleep(d.toMillis());
        Sleeper NONE = d -> { };
    }

    record Response(int status, byte[] body, HttpResponse<byte[]> raw) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        Optional<String> header(String name) {
            return raw == null ? Optional.empty() : raw.headers().firstValue(name);
        }

        Optional<Duration> retryAfter() {
            return header("Retry-After").flatMap(ProviderHttp::parseRetryAfter);
        }

        /** JSON do corpo; corpo vazio ou inválido vira {@link InvalidResponseException} (pendência 5.4). */
        JsonNode json() {
            try {
                var node = Json.mapper().readTree(body == null ? new byte[0] : body);
                if (node == null || node.isMissingNode()) {
                    throw new InvalidResponseException("Resposta vazia do provedor.");
                }
                return node;
            } catch (IOException e) {
                throw new InvalidResponseException("Resposta JSON inválida do provedor.");
            }
        }
    }

    /** Resposta HTTP não esperada (status fora do contrato). */
    static final class ApiException extends RuntimeException {
        private final int status;
        private final transient Duration retryAfter;

        ApiException(int status, Duration retryAfter) {
            super("Provider request failed with HTTP " + status + ".");
            this.status = status;
            this.retryAfter = retryAfter;
        }

        static ApiException from(Response response) {
            return new ApiException(response.status(), response.retryAfter().orElse(null));
        }

        int status() {
            return status;
        }

        Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }

    /** Corpo ausente, campo obrigatório faltando ou JSON inválido. */
    static final class InvalidResponseException extends RuntimeException {
        InvalidResponseException(String message) {
            super(message);
        }
    }

    /** Falha de transporte (rede, timeout, interrupção): o servidor pode ou não ter processado. */
    static final class TransportException extends RuntimeException {
        TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient client;
    private final int maximumRetryAttempts;
    private final Sleeper sleeper;
    private final Duration requestTimeout;

    ProviderHttp(HttpClient client, int maximumRetryAttempts, Sleeper sleeper, Duration requestTimeout) {
        this.client = client;
        this.maximumRetryAttempts = Math.max(0, maximumRetryAttempts);
        this.sleeper = sleeper;
        this.requestTimeout = requestTimeout;
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Envia uma requisição. {@code bearer} é consultado a cada tentativa (o token pode ser
     * renovado entre elas). Somente {@code retrySafe} repete em status transitório ou falha de rede.
     */
    Response send(Supplier<HttpRequest.Builder> factory, boolean retrySafe, Supplier<String> bearer) {
        for (var retry = 0; ; retry++) {
            var builder = factory.get().timeout(requestTimeout);
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer.get());
            }
            try {
                var raw = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                var response = new Response(raw.statusCode(), raw.body(), raw);
                if (!retrySafe || !isTransient(response.status()) || retry >= maximumRetryAttempts) {
                    return response;
                }
                pause(retryDelay(response.retryAfter().orElse(null), retry));
            } catch (IOException e) {
                if (!retrySafe || retry >= maximumRetryAttempts) {
                    throw new TransportException("Falha de comunicação com o provedor.", e);
                }
                pause(retryDelay(null, retry));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException("A chamada ao provedor foi interrompida.", e);
            }
        }
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("A espera entre tentativas foi interrompida.", e);
        }
    }

    static boolean isTransient(int status) {
        return status == 429 || status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static Duration retryDelay(Duration retryAfter, int retry) {
        if (retryAfter != null) {
            return retryAfter.isNegative() ? Duration.ZERO : retryAfter;
        }
        var exponential = Math.min(30_000L, 500L * (1L << Math.min(retry, 5)));
        return Duration.ofMillis(exponential + RANDOM.nextInt(251));
    }

    static Optional<Duration> parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        var trimmed = value.strip();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed))));
        } catch (NumberFormatException ignored) {
            try {
                var date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var remaining = Duration.between(Instant.now(), date);
                return Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
    }

    static String requiredText(JsonNode node, String property) {
        var value = node == null ? null : node.get(property);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidResponseException("Campo obrigatório ausente: " + property + ".");
        }
        return value.asText();
    }

    static URI resolve(URI base, String relative) {
        return base.resolve(relative);
    }
}
