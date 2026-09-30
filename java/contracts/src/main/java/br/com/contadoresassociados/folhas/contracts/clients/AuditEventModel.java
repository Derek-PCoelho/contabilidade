package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEventModel(
        UUID id,
        String entityType,
        String entityId,
        String action,
        String category,
        String severity,
        String redactedDataJson,
        OffsetDateTime timestampUtc,
        UUID correlationId) {
}
