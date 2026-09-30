package br.com.contadoresassociados.folhas.contracts.documents;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReviewAuditEvent(
        UUID id,
        String scopeKey,
        String actorId,
        OffsetDateTime timestampUtc,
        String action,
        UUID documentId,
        UUID groupId,
        String previousValue,
        String newValue,
        String reason,
        String correlationId) {
}
