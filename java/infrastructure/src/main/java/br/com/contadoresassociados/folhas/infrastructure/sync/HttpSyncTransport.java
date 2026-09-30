package br.com.contadoresassociados.folhas.infrastructure.sync;

import br.com.contadoresassociados.folhas.application.sync.SyncPorts;
import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncRequest;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncResponse;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient;

/**
 * Transporte HTTP da sincronização ({@code api/sync/clients}). Toda falha de rede, HTTP ou
 * JSON vira {@link SyncPorts.SyncTransportException} para o {@code SyncService} ficar offline e
 * reagendar (o .NET deixava {@code HttpRequestException}/{@code InvalidOperationException} vazar).
 */
public final class HttpSyncTransport implements SyncPorts.SyncTransport {

    static final String PATH = "api/sync/clients";

    private final CentralApiClient api;

    public HttpSyncTransport(CentralApiClient api) {
        this.api = api;
    }

    @Override
    public PushSyncResponse push(PushSyncRequest request) {
        try {
            var response = api.postJson(PATH, request);
            ensure(response, "push");
            return response.read(PushSyncResponse.class);
        } catch (CentralApiClient.CentralApiException e) {
            throw new SyncPorts.SyncTransportException(e.getMessage(), e.status(), e);
        }
    }

    @Override
    public PullSyncResponse pull(long checkpoint) {
        try {
            var response = api.get(PATH + "?checkpoint=" + Math.max(0, checkpoint));
            ensure(response, "pull");
            return response.read(PullSyncResponse.class);
        } catch (CentralApiClient.CentralApiException e) {
            throw new SyncPorts.SyncTransportException(e.getMessage(), e.status(), e);
        }
    }

    private static void ensure(CentralApiClient.JsonResponse response, String operation) {
        if (!response.ok()) {
            throw new SyncPorts.SyncTransportException("A sincronização (" + operation + ") falhou com HTTP "
                    + response.status() + ".", response.status(), null);
        }
    }
}
