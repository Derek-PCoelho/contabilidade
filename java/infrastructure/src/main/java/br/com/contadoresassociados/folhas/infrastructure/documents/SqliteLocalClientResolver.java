package br.com.contadoresassociados.folhas.infrastructure.documents;

import br.com.contadoresassociados.folhas.application.documents.ClientMatcher;
import br.com.contadoresassociados.folhas.application.documents.ClientResolver;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.infrastructure.clients.SqliteLocalClientCatalogService;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.CatalogRecords;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import java.util.List;

/**
 * Resolvedor do perfil local: lê o catálogo {@code local-client} do {@code cache.db} uma única
 * vez por lote (pendência 2.7: o .NET relia e desserializava o catálogo inteiro a cada documento).
 * Registro corrompido vira bloqueio {@code client.catalog_unreadable} em vez de derrubar a revisão.
 */
public final class SqliteLocalClientResolver implements ClientResolver {

    private final LocalDatabase database;

    public SqliteLocalClientResolver(LocalDatabase database) {
        this.database = database;
    }

    @Override
    public ClientResolutionResult resolve(ClientResolutionRequest request) {
        return resolveBatch(List.of(request)).getFirst();
    }

    @Override
    public List<ClientResolutionResult> resolveBatch(List<ClientResolutionRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<ClientDetails> catalog;
        try {
            catalog = database.read(c -> CatalogRecords.all(c, SqliteLocalClientCatalogService.CLIENT_RECORD_TYPE,
                    ClientDetails.class));
        } catch (RuntimeException e) {
            return requests.stream().map(r -> ClientResolutionResult.unresolved("client.catalog_unreadable")).toList();
        }
        return new ClientMatcher(catalog).resolveAll(requests);
    }
}
