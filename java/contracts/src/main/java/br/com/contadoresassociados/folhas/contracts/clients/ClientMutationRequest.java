package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.List;
import java.util.UUID;

public record ClientMutationRequest(
        long expectedVersion,
        PersonTypeModel personType,
        String legalNameOrFullName,
        String preferredName,
        String internalCode,
        String primaryTaxId,
        boolean isActive,
        UUID defaultSubjectTemplateId,
        UUID defaultBodyTemplateId,
        String notes,
        List<ClientIdentifierModel> identifiers,
        List<EstablishmentModel> establishments,
        List<RecipientModel> recipients,
        List<ClientPartnerModel> partners) {

    public ClientMutationRequest {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        establishments = establishments == null ? List.of() : List.copyOf(establishments);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        partners = partners == null ? List.of() : List.copyOf(partners);
    }
}
