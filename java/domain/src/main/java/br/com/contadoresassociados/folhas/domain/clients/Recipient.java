package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.time.LocalDate;
import java.util.UUID;

public final class Recipient {

    private final UUID id;
    private final UUID clientId;
    private final UUID organizationId;
    private UUID establishmentId;
    private String displayName;
    private String emailNormalized;
    private DeliveryRole deliveryRole;
    private UUID documentTypeId;
    private boolean primary;
    private boolean active;
    private LocalDate validFrom;
    private LocalDate validTo;

    Recipient(UUID id, UUID clientId, UUID organizationId, RecipientDraft draft) {
        this.id = id;
        this.clientId = clientId;
        this.organizationId = organizationId;
        apply(draft);
    }

    private Recipient(Recipient o) {
        id = o.id;
        clientId = o.clientId;
        organizationId = o.organizationId;
        establishmentId = o.establishmentId;
        displayName = o.displayName;
        emailNormalized = o.emailNormalized;
        deliveryRole = o.deliveryRole;
        documentTypeId = o.documentTypeId;
        primary = o.primary;
        active = o.active;
        validFrom = o.validFrom;
        validTo = o.validTo;
    }

    Recipient copy() {
        return new Recipient(this);
    }

    public boolean isCurrentlyValid(LocalDate today) {
        return active
                && (validFrom == null || !validFrom.isAfter(today))
                && (validTo == null || !validTo.isBefore(today));
    }

    void apply(RecipientDraft draft) {
        var name = Guards.required(draft.displayName(), 160, "recipient.display_name",
                "O nome do destinatário");
        var email = EmailAddress.normalize(draft.email());
        if (draft.validFrom() != null && draft.validTo() != null && draft.validFrom().isAfter(draft.validTo())) {
            throw new DomainValidationException("recipient.validity_range",
                    "O início da vigência do destinatário não pode ser depois do fim.");
        }
        establishmentId = Guards.normalizeOptionalId(draft.establishmentId());
        displayName = name;
        emailNormalized = email;
        deliveryRole = draft.deliveryRole() == null ? DeliveryRole.TO : draft.deliveryRole();
        documentTypeId = Guards.normalizeOptionalId(draft.documentTypeId());
        primary = draft.primary();
        active = draft.active();
        validFrom = draft.validFrom();
        validTo = draft.validTo();
    }

    void deactivate() {
        active = false;
        primary = false;
    }

    /** Chave de rota usada para unicidade (dentro do cliente e no banco). */
    String routeKey() {
        return establishmentId + ":" + emailNormalized + ":" + deliveryRole + ":" + documentTypeId;
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public UUID organizationId() { return organizationId; }
    public UUID establishmentId() { return establishmentId; }
    public String displayName() { return displayName; }
    public String emailNormalized() { return emailNormalized; }
    public DeliveryRole deliveryRole() { return deliveryRole; }
    public UUID documentTypeId() { return documentTypeId; }
    public boolean primary() { return primary; }
    public boolean active() { return active; }
    public LocalDate validFrom() { return validFrom; }
    public LocalDate validTo() { return validTo; }
}
