package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.UUID;

public record EmailSendPreflightResponse(
        UUID operationId,
        boolean authorized,
        boolean emailSendEnabled,
        String minimumApplicationVersion,
        UUID correlationId,
        String errorCode) {
}
