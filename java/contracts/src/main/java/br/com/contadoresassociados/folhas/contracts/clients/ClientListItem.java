package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientListItem(
        UUID id,
        PersonTypeModel personType,
        String displayName,
        String primaryTaxIdMasked,
        String internalCode,
        boolean isActive,
        long version,
        int establishmentCount,
        int recipientCount,
        OffsetDateTime updatedAtUtc) {
}
