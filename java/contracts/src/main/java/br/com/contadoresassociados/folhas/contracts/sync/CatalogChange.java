package br.com.contadoresassociados.folhas.contracts.sync;

import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import java.util.UUID;

public record CatalogChange(
        long checkpoint,
        SyncEntityType entityType,
        UUID entityId,
        long version,
        boolean deleted,
        ClientDetails client,
        MessageTemplateModel template) {
}
