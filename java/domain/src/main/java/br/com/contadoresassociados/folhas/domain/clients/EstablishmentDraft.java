package br.com.contadoresassociados.folhas.domain.clients;

import java.util.UUID;

public record EstablishmentDraft(
        UUID id,
        String cnpj,
        String legalName,
        String displayName,
        String internalCode,
        boolean headOffice,
        boolean active) {
}
