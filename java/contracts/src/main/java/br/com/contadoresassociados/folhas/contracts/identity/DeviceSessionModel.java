package br.com.contadoresassociados.folhas.contracts.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeviceSessionModel(
        UUID id,
        String deviceName,
        OffsetDateTime createdAtUtc,
        OffsetDateTime lastSeenAtUtc,
        OffsetDateTime revokedAtUtc,
        boolean isCurrent) {
}
