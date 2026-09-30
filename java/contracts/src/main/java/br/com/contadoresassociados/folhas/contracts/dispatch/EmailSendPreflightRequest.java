package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.UUID;

public record EmailSendPreflightRequest(
        UUID operationId,
        String providerKey,
        String dispatchFingerprint,
        int attachmentCount,
        String applicationVersion,
        DispatchOperationMode operationMode,
        int batchSize,
        int attemptNumber) {
}
