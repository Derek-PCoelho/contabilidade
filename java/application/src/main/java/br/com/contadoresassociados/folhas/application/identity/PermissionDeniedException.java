package br.com.contadoresassociados.folhas.application.identity;

import br.com.contadoresassociados.folhas.domain.identity.AppPermission;

public final class PermissionDeniedException extends RuntimeException {

    private final AppPermission permission;

    public PermissionDeniedException(AppPermission permission) {
        super("Seu perfil não tem a permissão necessária (" + permission.wireName() + ") para esta operação.");
        this.permission = permission;
    }

    public AppPermission permission() {
        return permission;
    }
}
