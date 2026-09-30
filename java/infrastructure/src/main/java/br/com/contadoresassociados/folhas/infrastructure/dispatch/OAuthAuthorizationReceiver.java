package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Recebe o redirecionamento OAuth em loopback (RFC 8252). */
public interface OAuthAuthorizationReceiver {

    record AuthorizationResponse(String code, String state, String error, URI redirectUri) {
    }

    AuthorizationResponse receive(Function<URI, URI> createAuthorizationUri, String expectedState,
            CancellationToken cancellationToken);

    /**
     * Abre o navegador do sistema e escuta {@code http://127.0.0.1:{porta}/oauth2/callback/}.
     * A página de retorno é a mesma do .NET ("Conta conectada" / "Conexão não concluída").
     */
    final class SystemBrowser implements OAuthAuthorizationReceiver {

        private final Duration timeout;
        private final java.util.function.Consumer<URI> browser;

        public SystemBrowser() {
            this(Duration.ofMinutes(5), SystemBrowser::openBrowser);
        }

        public SystemBrowser(Duration timeout, java.util.function.Consumer<URI> browser) {
            this.timeout = timeout;
            this.browser = browser;
        }

        @Override
        public AuthorizationResponse receive(Function<URI, URI> createAuthorizationUri, String expectedState,
                CancellationToken cancellationToken) {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível abrir a porta local de retorno OAuth.", e);
            }
            var redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/callback/");
            var future = new CompletableFuture<AuthorizationResponse>();
            server.createContext("/oauth2/callback/", exchange -> {
                var query = parseQuery(exchange.getRequestURI().getRawQuery());
                var response = new AuthorizationResponse(query.get("code"), query.get("state"), query.get("error"),
                        redirect);
                var valid = response.error() == null && response.code() != null && !response.code().isBlank()
                        && expectedState.equals(response.state());
                var bytes = page(valid).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                future.complete(response);
            });
            server.start();
            try {
                browser.accept(createAuthorizationUri.apply(redirect));
                var deadline = System.nanoTime() + timeout.toNanos();
                while (true) {
                    if (cancellationToken != null && cancellationToken.isCancellationRequested()) {
                        throw new OperationCancelledException("A conexão da conta foi cancelada.");
                    }
                    try {
                        return future.get(250, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        if (System.nanoTime() > deadline) {
                            throw new OperationCancelledException("O tempo para concluir a conexão expirou.");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCancelledException("A conexão da conta foi interrompida.");
            } catch (ExecutionException e) {
                throw new IllegalStateException("Falha ao receber o retorno OAuth.", e.getCause());
            } finally {
                server.stop(0);
            }
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

        static String page(boolean success) {
            var title = success ? "Conta conectada" : "Conexão não concluída";
            var detail = success ? "Você pode fechar esta janela e voltar ao Folhas da Michelly."
                    : "Volte ao Folhas da Michelly para tentar novamente.";
            return "<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>" + title + "</title>"
                    + "<body style=\"font-family:system-ui;padding:48px;color:#24221e\"><h1>" + title + "</h1><p>"
                    + detail + "</p></body></html>";
        }

        private static void openBrowser(URI uri) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(uri);
                    return;
                }
                var os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
                var command = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", uri.toString()}
                        : os.contains("mac") ? new String[] {"open", uri.toString()}
                        : new String[] {"xdg-open", uri.toString()};
                new ProcessBuilder(command).start();
            } catch (IOException e) {
                throw new IllegalStateException("Não foi possível abrir o navegador do sistema.", e);
            }
        }
    }
}
