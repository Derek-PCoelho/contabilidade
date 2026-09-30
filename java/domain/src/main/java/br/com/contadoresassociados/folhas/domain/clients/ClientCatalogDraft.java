package br.com.contadoresassociados.folhas.domain.clients;

import java.util.List;
import java.util.UUID;

public record ClientCatalogDraft(
        PersonType personType,
        String legalNameOrFullName,
        String preferredName,
        String internalCode,
        String primaryTaxId,
        boolean active,
        UUID defaultSubjectTemplateId,
        UUID defaultBodyTemplateId,
        String notes,
        List<ClientIdentifierDraft> identifiers,
        List<EstablishmentDraft> establishments,
        List<RecipientDraft> recipients,
        List<ClientPartnerDraft> partners) {

    public ClientCatalogDraft {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        establishments = establishments == null ? List.of() : List.copyOf(establishments);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        partners = partners == null ? List.of() : List.copyOf(partners);
    }
}
