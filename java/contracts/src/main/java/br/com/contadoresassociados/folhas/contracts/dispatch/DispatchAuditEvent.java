package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DispatchAuditEvent(
        UUID id,
        String scopeKey,
        String actorId,
        OffsetDateTime timestampUtc,
        String action,
        UUID batchId,
        UUID dispatchItemId,
        UUID groupId,
        String outcome,
        String errorCode,
        String correlationId) {
}
