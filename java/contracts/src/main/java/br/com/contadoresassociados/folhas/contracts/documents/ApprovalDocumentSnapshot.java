package br.com.contadoresassociados.folhas.contracts.documents;

import java.util.UUID;

public record ApprovalDocumentSnapshot(
        UUID documentId,
        String sha256,
        long revision,
        UUID clientId,
        UUID establishmentId,
        String periodKey,
        String semanticDuplicateKey) {
}
