package br.com.contadoresassociados.folhas.contracts.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientSyncItem(
        UUID id,
        String displayName,
        boolean isActive,
        long version,
        OffsetDateTime updatedAtUtc) {
}
