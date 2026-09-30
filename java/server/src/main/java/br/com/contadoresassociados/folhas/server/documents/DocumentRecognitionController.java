package br.com.contadoresassociados.folhas.server.documents;

import br.com.contadoresassociados.folhas.application.documents.ClientMatcher;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionBatchRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionBatchResponse;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.server.catalog.CatalogRepository;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code api/document-recognition}: resolução de cliente com o mesmo {@link ClientMatcher} do
 * perfil local (precedência .NET: CNPJ do cliente, CNPJ de estabelecimento, CPF, código, nome).
 *
 * <p>Pendência 4.6: {@code resolve-client/batch} resolve até 100 documentos com uma única leitura
 * do catálogo e conta como uma chamada no limite de taxa.
 */
@RestController
@RequestMapping("/api/document-recognition")
public class DocumentRecognitionController {

    public static final int MAX_BATCH = 100;

    private final CatalogRepository catalog;

    public DocumentRecognitionController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/resolve-client")
    @Endpoint(permissions = "clients.read", rate = Policy.WRITE)
    public ClientResolutionResult resolve(HttpServletRequest http, @RequestBody ClientResolutionRequest request) {
        validate(request);
        return matcher(RequestContext.require(http).organizationId()).resolve(request);
    }

    @PostMapping("/resolve-client/batch")
    @Endpoint(permissions = "clients.read", rate = Policy.WRITE)
    public ClientResolutionBatchResponse resolveBatch(HttpServletRequest http,
            @RequestBody ClientResolutionBatchRequest request) {
        if (request == null || request.items().isEmpty() || request.items().size() > MAX_BATCH) {
            throw ApiException.badRequest("recognition.batch_invalid",
                    "Envie entre 1 e " + MAX_BATCH + " documentos por lote.");
        }
        request.items().forEach(DocumentRecognitionController::validate);
        return new ClientResolutionBatchResponse(matcher(RequestContext.require(http).organizationId())
                .resolveAll(request.items()));
    }

    private ClientMatcher matcher(UUID organizationId) {
        return new ClientMatcher(catalog.allClients(organizationId));
    }

    static void validate(ClientResolutionRequest request) {
        if (request == null || request.fields().size() > 50 || request.fields().stream().anyMatch(f -> f == null
                || length(f.name()) > 100 || length(f.value()) > 500 || length(f.displayValue()) > 500
                || (f.evidence() != null && length(f.evidence().snippet()) > 1_000))) {
            throw ApiException.badRequest("recognition.payload_too_large",
                    "Os campos reconhecidos excedem os limites permitidos.");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
