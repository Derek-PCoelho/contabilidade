package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.UUID;

public record EstablishmentModel(
        UUID id,
        String cnpj,
        String legalName,
        String displayName,
        String internalCode,
        boolean isHeadOffice,
        boolean isActive) {
}
