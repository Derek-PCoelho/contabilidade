package br.com.contadoresassociados.folhas.contracts.dispatch;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import java.util.UUID;

public record DispatchAttachmentSnapshot(
        UUID documentId,
        String localPath,
        String fileName,
        String sha256,
        long fileSizeBytes,
        RecognizedDocumentType documentType) {
}
