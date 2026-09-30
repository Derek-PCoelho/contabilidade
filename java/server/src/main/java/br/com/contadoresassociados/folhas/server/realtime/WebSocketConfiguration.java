package br.com.contadoresassociados.folhas.server.realtime;

import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * {@code /hubs/sync}. O token vai no cabeçalho {@code Authorization} ou, como no SignalR, em
 * {@code access_token} na query (clientes WebSocket não enviam cabeçalhos customizados).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final SyncNotifier notifier;
    private final ApiAuthenticator authenticator;

    public WebSocketConfiguration(SyncNotifier notifier, ApiAuthenticator authenticator) {
        this.notifier = notifier;
        this.authenticator = authenticator;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifier, "/hubs/sync").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet)) {
                    return false;
                }
                var http = servlet.getServletRequest();
                var principal = authenticator.authenticate(http);
                if (principal.isEmpty()) {
                    var token = http.getParameter("access_token");
                    principal = token == null ? principal : authenticator.bearer(token);
                }
                if (principal.isEmpty()) {
                    response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return false;
                }
                attributes.put(SyncNotifier.PRINCIPAL_ATTRIBUTE, principal.get());
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                    Exception exception) {
                // nada
            }
        });
    }
}
