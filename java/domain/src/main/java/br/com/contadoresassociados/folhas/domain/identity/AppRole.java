package br.com.contadoresassociados.folhas.domain.identity;

import java.util.Arrays;
import java.util.Optional;

public enum AppRole {
    OWNER_TECHNICAL("OwnerTechnical", true),
    ADMINISTRATOR("Administrator", true),
    MANAGER("Manager", true),
    OPERATOR("Operator", false),
    AUDITOR("Auditor", false);

    private final String wireName;
    private final boolean privileged;

    AppRole(String wireName, boolean privileged) {
        this.wireName = wireName;
        this.privileged = privileged;
    }

    /** Nome usado no banco e nos tokens (compatível com a versão .NET). */
    public String wireName() {
        return wireName;
    }

    /** Papéis privilegiados exigem 2FA cadastrado. */
    public boolean privileged() {
        return privileged;
    }

    public static Optional<AppRole> fromWireName(String value) {
        return Arrays.stream(values()).filter(role -> role.wireName.equals(value)).findFirst();
    }
}
