package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.UUID;

public record DispatchRecipientSnapshot(
        UUID recipientId,
        String displayName,
        String email,
        String deliveryRole,
        UUID establishmentId) {
}
