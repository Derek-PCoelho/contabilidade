package br.com.contadoresassociados.folhas.server.realtime;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.contracts.sync.SyncNotification;
import br.com.contadoresassociados.folhas.server.security.ApiAuthenticator;
import br.com.contadoresassociados.folhas.server.security.Principal;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Canal {@code /hubs/sync} (substitui o hub SignalR). Cada conexão entra no grupo da
 * organização do token e recebe {@code {"type":"RecordsChanged","notification":{...}}}.
 *
 * <p>Pendência 4.8: a publicação é "fire-and-log" — acontece depois do commit, nunca lança
 * exceção para o controlador e uma sessão lenta ou quebrada é descartada sem afetar as demais.
 */
public final class SyncNotifier extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SyncNotifier.class);
    public static final String PRINCIPAL_ATTRIBUTE = "folhas.principal";

    private final Map<UUID, Set<WebSocketSession>> groups = new ConcurrentHashMap<>();
    private final ApiAuthenticator authenticator;

    public SyncNotifier(ApiAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var principal = (Principal) session.getAttributes().get(PRINCIPAL_ATTRIBUTE);
        if (principal == null || !principal.has("clients.read") || !authenticator.sessionActive(principal)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        var decorated = new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024);
        session.getAttributes().put("folhas.decorated", decorated);
        groups.computeIfAbsent(principal.organizationId(), k -> ConcurrentHashMap.newKeySet()).add(decorated);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var principal = (Principal) session.getAttributes().get(PRINCIPAL_ATTRIBUTE);
        var decorated = session.getAttributes().get("folhas.decorated");
        if (principal != null && decorated instanceof WebSocketSession d) {
            var group = groups.get(principal.organizationId());
            if (group != null) {
                group.remove(d);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // canal somente de saída
    }

    public void publish(UUID organizationId, SyncNotification notification) {
        try {
            var group = groups.get(organizationId);
            if (group == null || group.isEmpty()) {
                return;
            }
            var payload = new TextMessage(Json.write(Map.of("type", "RecordsChanged", "notification", notification)));
            for (var session : group) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(payload);
                    } else {
                        group.remove(session);
                    }
                } catch (IOException | RuntimeException e) {
                    group.remove(session);
                    LOG.debug("Sessão de notificação descartada: {}", e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Falha ao publicar notificação de sincronização (os dados já foram gravados).", e);
        }
    }

    public int connections(UUID organizationId) {
        var group = groups.get(organizationId);
        return group == null ? 0 : group.size();
    }
}
