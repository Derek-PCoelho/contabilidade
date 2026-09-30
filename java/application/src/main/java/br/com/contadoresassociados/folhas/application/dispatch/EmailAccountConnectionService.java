package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent;

import java.util.ArrayList;
import java.util.UUID;

/** Conectar/desconectar a conta de e-mail, sempre auditado no escopo corrente. */
public final class EmailAccountConnectionService {

    private final EmailAccountSession session;
    private final DispatchWorkflowStore store;
    private final DispatchExecutionContextAccessor contexts;
    private final Clock clock;

    public EmailAccountConnectionService(EmailAccountSession session, DispatchWorkflowStore store,
            DispatchExecutionContextAccessor contexts, Clock clock) {
        this.session = session;
        this.store = store;
        this.contexts = contexts;
        this.clock = clock;
    }

    public EmailAccountSession.Status status() {
        return session.status();
    }

    public EmailAccountSession.Status connect() {
        EmailAccountSession.Status status;
        try {
            status = session.connect();
        } catch (EmailAccountSession.EmailAccountSessionException e) {
            audit("email_provider_connected", "failed", e.code());
            throw e;
        }
        audit("email_provider_connected", status.connected() ? "connected" : "failed", status.errorCode());
        return status;
    }

    public void disconnect() {
        session.disconnect();
        audit("email_provider_disconnected", "disconnected", null);
    }

    private void audit(String action, String outcome, String errorCode) {
        var ctx = contexts.current();
        for (int attempt = 0; ; attempt++) {
            var ws = store.load(ctx.scopeKey());
            var events = new ArrayList<>(ws.auditEvents());
            events.add(new DispatchAuditEvent(UUID.randomUUID(), ctx.scopeKey(), ctx.actorId(), clock.nowUtc(), action,
                    null, null, null, outcome, errorCode, UUID.randomUUID().toString().replace("-", "")));
            try {
                store.save(ws.toBuilder().auditEvents(events).build(), ws.version());
                return;
            } catch (WorkspaceConflictException e) {
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }
}
