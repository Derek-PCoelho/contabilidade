package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.LocalDate;
import java.util.UUID;

public record RecipientModel(
        UUID id,
        UUID establishmentId,
        String displayName,
        String email,
        DeliveryRoleModel deliveryRole,
        UUID documentTypeId,
        boolean isPrimary,
        boolean isActive,
        LocalDate validFrom,
        LocalDate validTo) {
}
