package br.com.contadoresassociados.folhas.application.incidents;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchExecutionContextAccessor;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowStore;
import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import br.com.contadoresassociados.folhas.application.identity.PermissionDeniedException;
import br.com.contadoresassociados.folhas.application.security.SensitiveTextRedactor;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro de incidentes ligados a uma tentativa de entrega.
 * Pendência 2.10: abrir e mudar situação exige {@code incidents.manage}; consultar exige {@code audit.read}.
 * O store usa concorrência otimista por versão do workspace.
 */
public final class IncidentManagement {

    private IncidentManagement() {
    }

    public enum Category {
        POTENTIAL_WRONG_RECIPIENT, POTENTIAL_WRONG_ATTACHMENT, DUPLICATE_DELIVERY, AMBIGUOUS_PROVIDER_RESULT,
        CREDENTIAL_EXPOSURE, LOCAL_DATA_EXPOSURE, OTHER
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Status { OPEN, CONTAINED, INVESTIGATING, RESOLVED, CLOSED }

    public record Incident(UUID id, String scopeKey, UUID deliveryAttemptId, UUID batchId, UUID dispatchItemId,
            UUID groupId, Category category, Severity severity, Status status, String summary, String resolution,
            String detectedBy, OffsetDateTime detectedAtUtc, String updatedBy, OffsetDateTime updatedAtUtc,
            OffsetDateTime closedAtUtc, long version) {
    }

    public record AuditEvent(UUID id, String scopeKey, UUID incidentId, String actorId, OffsetDateTime timestampUtc,
            String action, Status previousStatus, Status currentStatus, String note) {
    }

    public record Workspace(String scopeKey, List<Incident> incidents, List<AuditEvent> auditEvents, long version) {
        public Workspace {
            incidents = List.copyOf(incidents);
            auditEvents = List.copyOf(auditEvents);
        }

        public static Workspace empty(String scopeKey) {
            return new Workspace(scopeKey, List.of(), List.of(), 0);
        }

        public long openHighOrCritical() {
            return incidents.stream().filter(i -> i.status() != Status.CLOSED && i.status() != Status.RESOLVED
                    && (i.severity() == Severity.HIGH || i.severity() == Severity.CRITICAL)).count();
        }
    }

    public record OpenRequest(UUID deliveryAttemptId, Category category, Severity severity, String summary) {
    }

    public record TransitionRequest(UUID incidentId, long expectedVersion, Status status, String note) {
    }

    public interface Store {
        Workspace load(String scopeKey);

        Workspace save(Workspace workspace, long expectedVersion);
    }

    public static final class IncidentException extends RuntimeException {
        private final String code;

        public IncidentException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class Service {
        private final Store store;
        private final DispatchWorkflowStore dispatchStore;
        private final DispatchExecutionContextAccessor contexts;
        private final SensitiveTextRedactor redactor;
        private final Clock clock;

        public Service(Store store, DispatchWorkflowStore dispatchStore, DispatchExecutionContextAccessor contexts,
                SensitiveTextRedactor redactor, Clock clock) {
            this.store = store;
            this.dispatchStore = dispatchStore;
            this.contexts = contexts;
            this.redactor = redactor;
            this.clock = clock;
        }

        public Workspace load() {
            var ctx = contexts.current();
            if (!ctx.has(AppPermission.AUDIT_READ) && !ctx.has(AppPermission.INCIDENTS_MANAGE)) {
                throw new PermissionDeniedException(AppPermission.AUDIT_READ);
            }
            return store.load(ctx.scopeKey());
        }

        public synchronized Workspace open(OpenRequest request) {
            Objects.requireNonNull(request, "request");
            var ctx = contexts.current();
            demandManage(ctx.has(AppPermission.INCIDENTS_MANAGE));
            if (request.category() == null || request.severity() == null) {
                throw new IncidentException("INCIDENT_CLASSIFICATION_REQUIRED", "Informe categoria e gravidade.");
            }
            var attempt = dispatchStore.load(ctx.scopeKey()).attempts().stream()
                    .filter(a -> a.id().equals(request.deliveryAttemptId())).findFirst()
                    .orElseThrow(() -> new IncidentException("INCIDENT_ATTEMPT_NOT_FOUND",
                            "Selecione uma tentativa registrada antes de abrir o incidente."));
            var ws = store.load(ctx.scopeKey());
            if (ws.incidents().stream().anyMatch(i -> i.deliveryAttemptId().equals(attempt.id())
                    && i.category() == request.category() && i.status() != Status.CLOSED)) {
                throw new IncidentException("INCIDENT_ALREADY_OPEN",
                        "Já existe um incidente ativo desta categoria para a tentativa.");
            }
            var summary = redactor.redact(requireText(request.summary(), 20, "INCIDENT_SUMMARY_REQUIRED"));
            var now = clock.nowUtc();
            var incident = new Incident(UUID.randomUUID(), ctx.scopeKey(), attempt.id(), attempt.batchId(),
                    attempt.dispatchItemId(), attempt.groupId(), request.category(), request.severity(), Status.OPEN,
                    summary, null, ctx.actorId(), now, ctx.actorId(), now, null, 1);
            var incidents = new ArrayList<>(ws.incidents());
            incidents.add(incident);
            var audit = new ArrayList<>(ws.auditEvents());
            audit.add(new AuditEvent(UUID.randomUUID(), ctx.scopeKey(), incident.id(), ctx.actorId(), now,
                    "incident_opened", null, Status.OPEN, summary));
            return save(new Workspace(ctx.scopeKey(), incidents, audit, ws.version()));
        }

        public synchronized Workspace transition(TransitionRequest request) {
            Objects.requireNonNull(request, "request");
            var ctx = contexts.current();
            demandManage(ctx.has(AppPermission.INCIDENTS_MANAGE));
            var ws = store.load(ctx.scopeKey());
            var incident = ws.incidents().stream().filter(i -> i.id().equals(request.incidentId())).findFirst()
                    .orElseThrow(() -> new IncidentException("INCIDENT_NOT_FOUND",
                            "Incidente não encontrado neste espaço de trabalho."));
            if (incident.version() != request.expectedVersion()) {
                throw new IncidentException("INCIDENT_VERSION_CONFLICT",
                        "O incidente foi alterado; recarregue antes de continuar.");
            }
            if (!canTransition(incident.status(), request.status())) {
                throw new IncidentException("INCIDENT_TRANSITION_INVALID", "Esta mudança de situação não é permitida.");
            }
            var closing = request.status() == Status.RESOLVED || request.status() == Status.CLOSED;
            var note = redactor.redact(requireText(request.note(), closing ? 10 : 3, "INCIDENT_NOTE_REQUIRED"));
            var now = clock.nowUtc();
            var changed = new Incident(incident.id(), incident.scopeKey(), incident.deliveryAttemptId(),
                    incident.batchId(), incident.dispatchItemId(), incident.groupId(), incident.category(),
                    incident.severity(), request.status(), incident.summary(), closing ? note : incident.resolution(),
                    incident.detectedBy(), incident.detectedAtUtc(), ctx.actorId(), now,
                    request.status() == Status.CLOSED ? now : null, incident.version() + 1);
            var incidents = ws.incidents().stream().map(i -> i.id().equals(incident.id()) ? changed : i).toList();
            var audit = new ArrayList<>(ws.auditEvents());
            audit.add(new AuditEvent(UUID.randomUUID(), ctx.scopeKey(), incident.id(), ctx.actorId(), now,
                    "incident_status_changed", incident.status(), changed.status(), note));
            return save(new Workspace(ctx.scopeKey(), incidents, audit, ws.version()));
        }

        private Workspace save(Workspace ws) {
            try {
                return store.save(ws, ws.version());
            } catch (WorkspaceConflictException e) {
                throw new IncidentException("INCIDENT_VERSION_CONFLICT",
                        "Os incidentes foram alterados por outra operação; recarregue antes de continuar.");
            }
        }

        private static void demandManage(boolean allowed) {
            if (!allowed) {
                throw new PermissionDeniedException(AppPermission.INCIDENTS_MANAGE);
            }
        }
    }

    static boolean canTransition(Status current, Status next) {
        return switch (current) {
            case OPEN -> next == Status.CONTAINED || next == Status.INVESTIGATING || next == Status.RESOLVED;
            case CONTAINED -> next == Status.INVESTIGATING || next == Status.RESOLVED;
            case INVESTIGATING -> next == Status.CONTAINED || next == Status.RESOLVED;
            case RESOLVED -> next == Status.CLOSED || next == Status.INVESTIGATING;
            case CLOSED -> false;
        };
    }

    static String requireText(String value, int minimum, String code) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() < minimum || normalized.length() > 2_000) {
            throw new IncidentException(code, "Informe entre " + minimum + " e 2.000 caracteres.");
        }
        return normalized;
    }
}
