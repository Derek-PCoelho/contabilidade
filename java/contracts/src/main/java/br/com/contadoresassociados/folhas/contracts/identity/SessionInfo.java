package br.com.contadoresassociados.folhas.contracts.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Resposta de /api/me: o Desktop lê a sessão daqui, sem decodificar o token (pendência 4.1). */
public record SessionInfo(
        UUID userId,
        UUID organizationId,
        String organizationName,
        String email,
        String displayName,
        List<String> roles,
        List<String> permissions,
        boolean mfaSatisfied,
        UUID deviceSessionId,
        OffsetDateTime expiresAtUtc) {

    public SessionInfo {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
