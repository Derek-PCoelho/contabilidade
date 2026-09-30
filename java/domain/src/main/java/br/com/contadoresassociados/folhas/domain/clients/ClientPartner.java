package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.util.UUID;

public final class ClientPartner {

    private final UUID id;
    private final UUID clientId;
    private final UUID organizationId;
    private String fullName;
    private String cpfNormalized;
    private String emailNormalized;
    private ClientPartnerRole role;
    private boolean active;

    ClientPartner(UUID id, UUID clientId, UUID organizationId, ClientPartnerDraft draft) {
        this.id = id;
        this.clientId = clientId;
        this.organizationId = organizationId;
        apply(draft);
    }

    private ClientPartner(ClientPartner o) {
        id = o.id;
        clientId = o.clientId;
        organizationId = o.organizationId;
        fullName = o.fullName;
        cpfNormalized = o.cpfNormalized;
        emailNormalized = o.emailNormalized;
        role = o.role;
        active = o.active;
    }

    ClientPartner copy() {
        return new ClientPartner(this);
    }

    void apply(ClientPartnerDraft draft) {
        var name = Guards.required(draft.fullName(), 200, "partner.full_name", "O nome do sócio");
        var cpf = draft.cpf() == null || draft.cpf().isBlank() ? null : BrazilianRegistration.normalizeCpf(draft.cpf());
        var email = draft.email() == null || draft.email().isBlank() ? null : EmailAddress.normalize(draft.email());
        fullName = name;
        cpfNormalized = cpf;
        emailNormalized = email;
        role = draft.role() == null ? ClientPartnerRole.PARTNER : draft.role();
        active = draft.active();
    }

    void deactivate() {
        active = false;
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public UUID organizationId() { return organizationId; }
    public String fullName() { return fullName; }
    public String cpfNormalized() { return cpfNormalized; }
    public String emailNormalized() { return emailNormalized; }
    public ClientPartnerRole role() { return role; }
    public boolean active() { return active; }
}
