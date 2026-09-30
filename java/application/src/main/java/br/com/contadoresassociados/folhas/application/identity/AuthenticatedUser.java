package br.com.contadoresassociados.folhas.application.identity;

import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        UUID deviceSessionId,
        String displayName,
        Set<AppPermission> permissions,
        boolean multiFactorAuthenticated) {

    public AuthenticatedUser {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(AppPermission permission) {
        return permissions.contains(permission);
    }

    /** Lança {@link PermissionDeniedException} se faltar a permissão (pendência 8.7). */
    public void require(AppPermission permission) {
        if (!has(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }
}
