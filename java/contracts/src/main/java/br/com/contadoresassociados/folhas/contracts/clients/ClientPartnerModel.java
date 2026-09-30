package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.UUID;

public record ClientPartnerModel(
        UUID id,
        String fullName,
        String cpf,
        ClientPartnerRoleModel role,
        boolean isActive,
        String email) {
}
