package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.util.Locale;
import java.util.UUID;

public final class Establishment {

    private final UUID id;
    private final UUID clientId;
    private final UUID organizationId;
    private String cnpjNormalized;
    private String cnpjRoot;
    private String legalName;
    private String displayName;
    private String internalCode;
    private boolean headOffice;
    private boolean active;

    Establishment(UUID id, UUID clientId, UUID organizationId, EstablishmentDraft draft, String clientTaxId) {
        this.id = id;
        this.clientId = clientId;
        this.organizationId = organizationId;
        apply(draft, clientTaxId);
    }

    private Establishment(Establishment o) {
        id = o.id;
        clientId = o.clientId;
        organizationId = o.organizationId;
        cnpjNormalized = o.cnpjNormalized;
        cnpjRoot = o.cnpjRoot;
        legalName = o.legalName;
        displayName = o.displayName;
        internalCode = o.internalCode;
        headOffice = o.headOffice;
        active = o.active;
    }

    Establishment copy() {
        return new Establishment(this);
    }

    void apply(EstablishmentDraft draft, String clientTaxId) {
        var cnpj = BrazilianRegistration.normalizeCnpj(draft.cnpj());
        var root = cnpj.substring(0, BrazilianRegistration.CNPJ_ROOT_LENGTH);
        if (!root.equals(BrazilianRegistration.cnpjRoot(clientTaxId))) {
            throw new DomainValidationException("establishment.root_mismatch",
                    "O CNPJ do estabelecimento deve ter a mesma raiz do CNPJ da empresa.");
        }
        var legal = Guards.required(draft.legalName(), 200, "establishment.legal_name",
                "A razão social do estabelecimento");
        var display = Guards.required(draft.displayName(), 200, "establishment.display_name",
                "O nome de exibição do estabelecimento");
        var code = Guards.optional(draft.internalCode(), 80, "establishment.internal_code",
                "O código interno do estabelecimento");
        cnpjNormalized = cnpj;
        cnpjRoot = root;
        legalName = legal;
        displayName = display;
        internalCode = code == null ? null : code.toUpperCase(Locale.ROOT);
        headOffice = draft.headOffice();
        active = draft.active();
    }

    void deactivate() {
        active = false;
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public UUID organizationId() { return organizationId; }
    public String cnpjNormalized() { return cnpjNormalized; }
    public String cnpjRoot() { return cnpjRoot; }
    public String legalName() { return legalName; }
    public String displayName() { return displayName; }
    public String internalCode() { return internalCode; }
    public boolean headOffice() { return headOffice; }
    public boolean active() { return active; }
}
