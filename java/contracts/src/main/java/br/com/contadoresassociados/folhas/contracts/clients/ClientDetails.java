package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ClientDetails(
        UUID id,
        PersonTypeModel personType,
        String legalNameOrFullName,
        String preferredName,
        String internalCode,
        String primaryTaxId,
        boolean isActive,
        UUID defaultSubjectTemplateId,
        UUID defaultBodyTemplateId,
        String notes,
        long version,
        OffsetDateTime createdAtUtc,
        OffsetDateTime updatedAtUtc,
        List<ClientIdentifierModel> identifiers,
        List<EstablishmentModel> establishments,
        List<RecipientModel> recipients,
        List<ClientPartnerModel> partners) {

    public ClientDetails {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        establishments = establishments == null ? List.of() : List.copyOf(establishments);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        partners = partners == null ? List.of() : List.copyOf(partners);
    }
}
