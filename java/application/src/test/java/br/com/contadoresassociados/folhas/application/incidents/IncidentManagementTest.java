package br.com.contadoresassociados.folhas.application.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchExecutionContext;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowStore;
import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import br.com.contadoresassociados.folhas.application.identity.PermissionDeniedException;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncidentManagementTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    Set<AppPermission> perms = EnumSet.allOf(AppPermission.class);
    IncidentManagement.Workspace saved;
    final UUID attemptId = UUID.randomUUID();

    IncidentManagement.Service service() {
        var now = NOW.atOffset(ZoneOffset.UTC);
        var attempt = new DeliveryAttempt(attemptId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                DispatchOperationMode.TEST, DeliveryAttemptState.AMBIGUOUS, "fake.local", "k", "fp", null, null, null,
                null, now, now);
        DispatchWorkflowStore dispatch = new DispatchWorkflowStore() {
            @Override public DispatchWorkspace load(String s) {
                return DispatchWorkspace.empty(s).toBuilder().attempts(List.of(attempt)).build();
            }
            @Override public DispatchWorkspace save(DispatchWorkspace w, long v) { return w; }
        };
        IncidentManagement.Store store = new IncidentManagement.Store() {
            @Override public IncidentManagement.Workspace load(String s) {
                return saved == null ? IncidentManagement.Workspace.empty(s) : saved;
            }
            @Override public IncidentManagement.Workspace save(IncidentManagement.Workspace w, long v) {
                var cur = saved == null ? 0 : saved.version();
                if (cur != v) {
                    throw new WorkspaceConflictException(w.scopeKey(), v, cur);
                }
                saved = new IncidentManagement.Workspace(w.scopeKey(), w.incidents(), w.auditEvents(), cur + 1);
                return saved;
            }
        };
        return new IncidentManagement.Service(store, dispatch,
                () -> new DispatchExecutionContext("org:1|local", "ator", "Ana", perms),
                (v, m) -> v.replace("529.982.247-25", "***"), Clock.fixed(NOW));
    }

    @Test
    void lifecycleWithRedactionAndTransitions() {
        var svc = service();
        var ws = svc.open(new IncidentManagement.OpenRequest(attemptId,
                IncidentManagement.Category.AMBIGUOUS_PROVIDER_RESULT, IncidentManagement.Severity.HIGH,
                "Resultado incerto para o CPF 529.982.247-25 no envio"));
        var inc = ws.incidents().getFirst();
        assertThat(inc.summary()).doesNotContain("529.982");
        assertThat(ws.openHighOrCritical()).isEqualTo(1);
        assertThatThrownBy(() -> svc.open(new IncidentManagement.OpenRequest(attemptId,
                IncidentManagement.Category.AMBIGUOUS_PROVIDER_RESULT, IncidentManagement.Severity.LOW,
                "Duplicado de propósito para testar bloqueio")))
                .hasMessageContaining("Já existe");
        assertThatThrownBy(() -> svc.transition(new IncidentManagement.TransitionRequest(inc.id(), 1,
                IncidentManagement.Status.CLOSED, "fechar direto")))
                .hasMessageContaining("não é permitida");
        ws = svc.transition(new IncidentManagement.TransitionRequest(inc.id(), 1, IncidentManagement.Status.RESOLVED,
                "Conferido no Gmail: não enviado"));
        assertThat(ws.incidents().getFirst().resolution()).startsWith("Conferido");
        assertThatThrownBy(() -> svc.transition(new IncidentManagement.TransitionRequest(inc.id(), 1,
                IncidentManagement.Status.CLOSED, "Versão antiga do incidente")))
                .hasMessageContaining("alterado");
    }

    @Test
    void requiresIncidentsManagePermission() {
        perms = EnumSet.of(AppPermission.AUDIT_READ);
        var svc = service();
        assertThat(svc.load().incidents()).isEmpty();
        assertThatThrownBy(() -> svc.open(new IncidentManagement.OpenRequest(attemptId,
                IncidentManagement.Category.OTHER, IncidentManagement.Severity.LOW, "Resumo suficiente para abrir")))
                .isInstanceOf(PermissionDeniedException.class);
    }
}
