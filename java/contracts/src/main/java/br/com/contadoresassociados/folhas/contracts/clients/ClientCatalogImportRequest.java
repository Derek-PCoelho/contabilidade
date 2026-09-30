package br.com.contadoresassociados.folhas.contracts.clients;

public record ClientCatalogImportRequest(
        boolean dryRun,
        boolean overwriteExisting,
        ClientCatalogTransferDocument document) {
}
