package br.com.contadoresassociados.folhas.contracts.clients;

import java.util.List;

public record ClientCatalogImportResult(
        boolean dryRun,
        int clientsCreated,
        int clientsUpdated,
        int templatesCreated,
        int templatesUpdated,
        List<String> warnings) {

    public ClientCatalogImportResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
