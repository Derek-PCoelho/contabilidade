package br.com.contadoresassociados.folhas.contracts.clients;

import java.time.OffsetDateTime;
import java.util.List;

public record ClientCatalogTransferDocument(
        int formatVersion,
        OffsetDateTime exportedAtUtc,
        List<ClientDetails> clients,
        List<MessageTemplateModel> templates) {

    public ClientCatalogTransferDocument {
        clients = clients == null ? List.of() : List.copyOf(clients);
        templates = templates == null ? List.of() : List.copyOf(templates);
    }
}
