package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.util.Set;

/** Contexto de execução. {@code actorDisplayName} alimenta o placeholder {{operador.nome}} (pendência 2.10). */
public record DispatchExecutionContext(String scopeKey, String actorId, String actorDisplayName,
        Set<AppPermission> permissions) {

    public DispatchExecutionContext {
        permissions = Set.copyOf(permissions);
    }

    public boolean has(AppPermission permission) {
        return permissions.contains(permission);
    }
}
