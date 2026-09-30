package br.com.contadoresassociados.folhas.domain.clients;

import java.time.LocalDate;
import java.util.UUID;

public record RecipientDraft(
        UUID id,
        UUID establishmentId,
        String displayName,
        String email,
        DeliveryRole deliveryRole,
        UUID documentTypeId,
        boolean primary,
        boolean active,
        LocalDate validFrom,
        LocalDate validTo) {
}
