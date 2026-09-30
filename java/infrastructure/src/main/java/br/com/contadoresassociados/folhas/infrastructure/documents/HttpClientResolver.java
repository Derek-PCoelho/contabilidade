package br.com.contadoresassociados.folhas.infrastructure.documents;

import br.com.contadoresassociados.folhas.application.documents.ClientResolver;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionBatchRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionBatchResponse;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolvedor do perfil conectado ({@code api/document-recognition/resolve-client}).
 *
 * <p>Pendência 4.6: {@link #resolveBatch} usa {@code resolve-client/batch} em blocos de até 100,
 * com recuo para chamadas individuais quando o servidor ainda não tem o endpoint. Os pedidos já
 * chegam minimizados ({@link ClientResolver#minimize}). Falhas nunca lançam exceção: viram
 * bloqueios estáveis (offline, autenticação, indisponível), como no .NET.
 */
public final class HttpClientResolver implements ClientResolver {

    static final String PATH = "api/document-recognition/resolve-client";
    static final String BATCH_PATH = PATH + "/batch";
    static final int BATCH_SIZE = 100;

    private final CentralApiClient api;

    public HttpClientResolver(CentralApiClient api) {
        this.api = api;
    }

    @Override
    public ClientResolutionResult resolve(ClientResolutionRequest request) {
        try {
            var response = api.postJson(PATH, request);
            if (!response.ok()) {
                return ClientResolutionResult.unresolved(blocker(response.status()));
            }
            return response.read(ClientResolutionResult.class);
        } catch (CentralApiClient.CentralApiException e) {
            return ClientResolutionResult.unresolved(e.status() == 0 ? "client.resolution_offline"
                    : "client.resolution_empty_response");
        }
    }

    @Override
    public List<ClientResolutionResult> resolveBatch(List<ClientResolutionRequest> requests) {
        var results = new ArrayList<ClientResolutionResult>(requests.size());
        for (var start = 0; start < requests.size(); start += BATCH_SIZE) {
            var chunk = requests.subList(start, Math.min(requests.size(), start + BATCH_SIZE));
            results.addAll(resolveChunk(chunk));
        }
        return results;
    }

    private List<ClientResolutionResult> resolveChunk(List<ClientResolutionRequest> chunk) {
        if (chunk.size() == 1) {
            return List.of(resolve(chunk.getFirst()));
        }
        try {
            var response = api.postJson(BATCH_PATH, new ClientResolutionBatchRequest(chunk));
            if (response.status() == 404 || response.status() == 405) {
                return chunk.stream().map(this::resolve).toList();
            }
            if (!response.ok()) {
                var blocker = blocker(response.status());
                return chunk.stream().map(r -> ClientResolutionResult.unresolved(blocker)).toList();
            }
            var results = response.read(ClientResolutionBatchResponse.class).results();
            if (results.size() != chunk.size()) {
                return chunk.stream().map(r -> ClientResolutionResult.unresolved("client.resolution_empty_response"))
                        .toList();
            }
            return results;
        } catch (CentralApiClient.CentralApiException e) {
            var blocker = e.status() == 0 ? "client.resolution_offline" : "client.resolution_empty_response";
            return chunk.stream().map(r -> ClientResolutionResult.unresolved(blocker)).toList();
        }
    }

    private static String blocker(int status) {
        return switch (status) {
            case 401 -> "client.resolution_authentication_required";
            case 429 -> "client.resolution_throttled";
            default -> "client.resolution_unavailable";
        };
    }
}
