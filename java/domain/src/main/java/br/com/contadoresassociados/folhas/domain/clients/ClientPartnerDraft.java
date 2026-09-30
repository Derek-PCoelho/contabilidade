package br.com.contadoresassociados.folhas.domain.clients;

import java.util.UUID;

public record ClientPartnerDraft(
        UUID id,
        String fullName,
        String cpf,
        ClientPartnerRole role,
        boolean active,
        String email) {
}
