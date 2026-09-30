package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DispatchApprovalSnapshot(
        UUID id,
        UUID dispatchItemId,
        long itemRevision,
        UUID reviewApprovalId,
        String reviewContentHash,
        String dispatchFingerprint,
        String approvedBy,
        OffsetDateTime approvedAtUtc) {
}
