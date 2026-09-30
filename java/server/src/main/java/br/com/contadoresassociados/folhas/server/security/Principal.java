package br.com.contadoresassociados.folhas.server.security;

import br.com.contadoresassociados.folhas.application.identity.AuthenticatedUser;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Usuária autenticada numa requisição de API (token assinado ou esquema de desenvolvimento). */
public record Principal(
        UUID userId,
        UUID organizationId,
        UUID deviceSessionId,
        String email,
        String displayName,
        List<String> roles,
        Set<String> permissions,
        boolean mfa,
        Instant expiresAt) {

    public Principal {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public AuthenticatedUser toAuthenticatedUser() {
        var mapped = permissions.stream().map(AppPermission::fromWireName).flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toSet());
        return new AuthenticatedUser(userId, organizationId, deviceSessionId, displayName, mapped, mfa);
    }
}
