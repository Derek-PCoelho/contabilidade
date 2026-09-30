package br.com.contadoresassociados.folhas.contracts.documents;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record GroupApprovalSnapshot(
        UUID id,
        UUID groupId,
        long groupRevision,
        String contentHash,
        String approvedBy,
        OffsetDateTime approvedAtUtc,
        List<ApprovalDocumentSnapshot> documents) {

    public GroupApprovalSnapshot {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
