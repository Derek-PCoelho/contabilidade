package br.com.contadoresassociados.folhas.domain.identity;

import java.util.Arrays;
import java.util.Optional;

public enum AppPermission {
    CLIENTS_READ("clients.read"),
    CLIENTS_WRITE("clients.write"),
    /** Novo (pendência 4.13): exportar o catálogo inteiro é permissão própria e exige MFA. */
    CLIENTS_EXPORT("clients.export"),
    TEMPLATES_READ("templates.read"),
    TEMPLATES_WRITE("templates.write"),
    DOCUMENTS_PROCESS("documents.process"),
    BATCH_APPROVE("batch.approve"),
    EMAIL_DRAFT("email.draft"),
    EMAIL_SEND("email.send"),
    AUDIT_READ("audit.read"),
    AUDIT_EXPORT("audit.export"),
    USERS_MANAGE("users.manage"),
    RECOGNITION_MANAGE("recognition.manage"),
    SETTINGS_MANAGE("settings.manage"),
    /** Novo (pendência 2.10): incidentes e checklist do piloto têm permissão própria. */
    INCIDENTS_MANAGE("incidents.manage");

    private final String wireName;

    AppPermission(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<AppPermission> fromWireName(String value) {
        return Arrays.stream(values()).filter(permission -> permission.wireName.equals(value)).findFirst();
    }
}
