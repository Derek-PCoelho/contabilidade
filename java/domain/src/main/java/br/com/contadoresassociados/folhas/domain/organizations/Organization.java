package br.com.contadoresassociados.folhas.domain.organizations;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Organização (tenant). Pendência 1.5: agora pode ser desativada e reativada. */
public final class Organization {

    private final UUID id;
    private String name;
    private final String slug;
    private boolean active;
    private final Instant createdAtUtc;
    private Instant updatedAtUtc;

    public Organization(UUID id, String name, String slug, Instant now) {
        if (Guards.isEmpty(id)) {
            throw new DomainValidationException("organization.empty_id", "A organização precisa de um identificador.");
        }
        this.id = id;
        this.name = Guards.required(name, 160, "organization.name", "O nome da organização");
        this.slug = normalizeSlug(slug);
        this.active = true;
        this.createdAtUtc = now;
        this.updatedAtUtc = now;
    }

    public static Organization restore(UUID id, String name, String slug, boolean active, Instant created,
            Instant updated) {
        var organization = new Organization(id, name, slug, created);
        organization.active = active;
        organization.updatedAtUtc = updated;
        return organization;
    }

    public void rename(String newName, Instant now) {
        name = Guards.required(newName, 160, "organization.name", "O nome da organização");
        updatedAtUtc = now;
    }

    public void deactivate(Instant now) {
        active = false;
        updatedAtUtc = now;
    }

    public void reactivate(Instant now) {
        active = true;
        updatedAtUtc = now;
    }

    static String normalizeSlug(String value) {
        var slug = Guards.required(value, 80, "organization.slug", "O identificador curto").toLowerCase(Locale.ROOT);
        for (var i = 0; i < slug.length(); i++) {
            var c = slug.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                throw new DomainValidationException("organization.slug_invalid",
                        "O identificador curto aceita somente letras sem acento, números e hífen.");
            }
        }
        return slug;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String slug() { return slug; }
    public boolean active() { return active; }
    public Instant createdAtUtc() { return createdAtUtc; }
    public Instant updatedAtUtc() { return updatedAtUtc; }
}
