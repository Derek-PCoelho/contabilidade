package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Servidor HTTP local para simular Google, Microsoft Graph e a API central nos testes. */
final class StubServer implements AutoCloseable {

    record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().getFirst()).orElse(null);
        }

        String target() {
            return method + " " + path + (query == null ? "" : "?" + query);
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/json"));
        }

        static Reply of(int status) {
            return new Reply(status, "", Map.of());
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private volatile Function<Request, Reply> handler = r -> Reply.of(404);

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var uri = exchange.getRequestURI();
            var request = new Request(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes());
            requests.add(request);
            Reply reply;
            try {
                reply = handler.apply(request);
            } catch (RuntimeException e) {
                reply = Reply.json(500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    void handle(Function<Request, Reply> value) {
        this.handler = value;
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    List<String> targets() {
        return requests.stream().map(Request::target).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
