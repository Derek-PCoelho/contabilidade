package br.com.contadoresassociados.folhas.domain.identity;

import static br.com.contadoresassociados.folhas.domain.identity.AppPermission.*;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Matriz papel → permissões (fonte única para servidor e desktop). */
public final class RolePermissionCatalog {

    private static final Map<AppRole, Set<AppPermission>> BY_ROLE = new EnumMap<>(AppRole.class);

    static {
        BY_ROLE.put(AppRole.OWNER_TECHNICAL, EnumSet.allOf(AppPermission.class));
        BY_ROLE.put(AppRole.ADMINISTRATOR, EnumSet.allOf(AppPermission.class));
        BY_ROLE.put(AppRole.MANAGER, EnumSet.of(CLIENTS_READ, CLIENTS_WRITE, CLIENTS_EXPORT, TEMPLATES_READ,
                TEMPLATES_WRITE, DOCUMENTS_PROCESS, BATCH_APPROVE, EMAIL_DRAFT, EMAIL_SEND, AUDIT_READ, AUDIT_EXPORT,
                USERS_MANAGE, INCIDENTS_MANAGE));
        BY_ROLE.put(AppRole.OPERATOR, EnumSet.of(CLIENTS_READ, TEMPLATES_READ, DOCUMENTS_PROCESS, EMAIL_DRAFT));
        BY_ROLE.put(AppRole.AUDITOR, EnumSet.of(CLIENTS_READ, TEMPLATES_READ, AUDIT_READ, AUDIT_EXPORT));
    }

    private RolePermissionCatalog() {
    }

    public static Set<AppPermission> forRole(AppRole role) {
        return Set.copyOf(BY_ROLE.getOrDefault(role, EnumSet.noneOf(AppPermission.class)));
    }

    public static Set<AppPermission> forRoles(Collection<AppRole> roles) {
        var result = EnumSet.noneOf(AppPermission.class);
        roles.forEach(role -> result.addAll(forRole(role)));
        return Set.copyOf(result);
    }
}
