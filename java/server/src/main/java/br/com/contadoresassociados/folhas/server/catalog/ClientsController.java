package br.com.contadoresassociados.folhas.server.catalog;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportResult;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogTransferDocument;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientReadinessResponse;
import br.com.contadoresassociados.folhas.contracts.clients.ClientSearchResponse;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.sync.SyncNotification;
import br.com.contadoresassociados.folhas.server.realtime.SyncNotifier;
import br.com.contadoresassociados.folhas.server.security.Principal;
import br.com.contadoresassociados.folhas.server.sync.AuditLog;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code api/clients} — mesmas rotas do .NET mais lote, status e arquivamento (4.19/6.13). */
@RestController
@RequestMapping("/api/clients")
public class ClientsController {

    public record BatchRequest(List<UUID> clientIds) {
    }

    public record StatusRequest(long expectedVersion, boolean isActive) {
    }

    private final CatalogRepository repository;
    private final SyncNotifier notifier;
    private final Clock clock;
    private final Db db;

    public ClientsController(CatalogRepository repository, SyncNotifier notifier, Clock clock, Db db) {
        this.repository = repository;
        this.notifier = notifier;
        this.clock = clock;
        this.db = db;
    }

    @GetMapping
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public ClientSearchResponse search(HttpServletRequest http, @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive, @RequestParam(required = false) String personType,
            @RequestParam(defaultValue = "0") int skip, @RequestParam(defaultValue = "50") int take) {
        var user = RequestContext.require(http);
        return repository.search(user.organizationId(), search, isActive, personType(personType), skip, take);
    }

    @GetMapping("/{clientId}")
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public ClientDetails get(HttpServletRequest http, @PathVariable UUID clientId) {
        return repository.get(RequestContext.require(http).organizationId(), clientId)
                .orElseThrow(() -> ApiException.notFound("Cliente não encontrado."));
    }

    @PostMapping("/batch")
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public List<ClientDetails> batch(HttpServletRequest http, @RequestBody BatchRequest request) {
        var ids = request == null || request.clientIds() == null ? List.<UUID>of() : request.clientIds();
        if (ids.size() > CatalogRepository.MAX_BATCH) {
            throw ApiException.badRequest("catalog.batch_too_large",
                    "Consulte no máximo " + CatalogRepository.MAX_BATCH + " clientes por vez.");
        }
        return repository.getMany(RequestContext.require(http).organizationId(), ids);
    }

    @GetMapping("/{clientId}/readiness")
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public ClientReadinessResponse readiness(HttpServletRequest http, @PathVariable UUID clientId) {
        return repository.readiness(RequestContext.require(http).organizationId(), clientId, clock.accountingDate())
                .orElseThrow(() -> ApiException.notFound("Cliente não encontrado."));
    }

    @GetMapping("/{clientId}/audit")
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public List<AuditEventModel> audit(HttpServletRequest http, @PathVariable UUID clientId,
            @RequestParam(defaultValue = "50") int take) {
        return repository.audit(RequestContext.require(http).organizationId(), clientId, take);
    }

    @PostMapping
    @Endpoint(permissions = "clients.write", mfa = true, rate = Policy.WRITE)
    public ResponseEntity<ClientDetails> create(HttpServletRequest http, @RequestBody ClientMutationRequest request) {
        var user = RequestContext.require(http);
        var written = repository.createClient(actor(user), require(request), UUID.randomUUID());
        publish(user, written.checkpoint(), "client-catalog");
        return ResponseEntity.created(java.net.URI.create("/api/clients/" + written.value().id())).body(written.value());
    }

    @PutMapping("/{clientId}")
    @Endpoint(permissions = "clients.write", mfa = true, rate = Policy.WRITE)
    public ClientDetails update(HttpServletRequest http, @PathVariable UUID clientId,
            @RequestBody ClientMutationRequest request) {
        var user = RequestContext.require(http);
        var written = repository.updateClient(actor(user), clientId, require(request), UUID.randomUUID());
        publish(user, written.checkpoint(), "client-catalog");
        return written.value();
    }

    @PostMapping("/{clientId}/status")
    @Endpoint(permissions = "clients.write", mfa = true, rate = Policy.WRITE)
    public ClientDetails status(HttpServletRequest http, @PathVariable UUID clientId,
            @RequestBody StatusRequest request) {
        var user = RequestContext.require(http);
        var body = require(request);
        var written = repository.setClientActive(actor(user), clientId, body.expectedVersion(), body.isActive(),
                UUID.randomUUID());
        publish(user, written.checkpoint(), "client-catalog");
        return written.value();
    }

    @PostMapping("/{clientId}/archive")
    @Endpoint(permissions = "clients.write", mfa = true, rate = Policy.WRITE)
    public ResponseEntity<Void> archive(HttpServletRequest http, @PathVariable UUID clientId,
            @RequestBody ArchiveRequest request) {
        var user = RequestContext.require(http);
        var written = repository.archiveClient(actor(user), clientId, require(request), UUID.randomUUID());
        publish(user, written.checkpoint(), "client-catalog");
        return ResponseEntity.noContent().build();
    }

    /** Pendência 4.13: exportar o catálogo inteiro exige permissão própria, MFA e fica auditado. */
    @GetMapping("/catalog/export")
    @Endpoint(permissions = {"clients.export", "templates.read"}, mfa = true, rate = Policy.SENSITIVE)
    public ClientCatalogTransferDocument export(HttpServletRequest http) {
        var user = RequestContext.require(http);
        var document = repository.export(user.organizationId());
        db.tenant(user.organizationId(), Db.Isolation.READ_COMMITTED, c -> AuditLog.append(c, user.organizationId(),
                user.userId(), user.deviceSessionId(), "client-catalog", user.organizationId().toString(), "exported",
                "registration", "warning", Map.of("clients", document.clients().size(), "templates",
                        document.templates().size()), clock.now(), UUID.randomUUID()));
        return document;
    }

    @PostMapping("/catalog/import")
    @Endpoint(permissions = {"clients.write", "templates.write"}, mfa = true, rate = Policy.SENSITIVE)
    public ClientCatalogImportResult importCatalog(HttpServletRequest http,
            @RequestBody ClientCatalogImportRequest request) {
        var user = RequestContext.require(http);
        var written = repository.importCatalog(actor(user), require(request), UUID.randomUUID());
        if (!written.value().dryRun()) {
            publish(user, written.checkpoint(), "client-catalog");
        }
        return written.value();
    }

    private void publish(Principal user, long checkpoint, String entityType) {
        if (checkpoint > 0) {
            notifier.publish(user.organizationId(), new SyncNotification(checkpoint, entityType));
        }
    }

    static CatalogRepository.Actor actor(Principal user) {
        return new CatalogRepository.Actor(user.organizationId(), user.userId(), user.deviceSessionId());
    }

    static <T> T require(T body) {
        if (body == null) {
            throw ApiException.badRequest("request.invalid", "O corpo da solicitação está vazio.");
        }
        return body;
    }

    /** Aceita o nome PascalCase do .NET ({@code LegalEntity}), o nome Java ou o ordinal. */
    static PersonTypeModel personType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var v = value.strip();
        if (v.chars().allMatch(Character::isDigit)) {
            var index = Integer.parseInt(v);
            if (index < 0 || index >= PersonTypeModel.values().length) {
                throw ApiException.badRequest("request.invalid", "Tipo de pessoa inválido.");
            }
            return PersonTypeModel.values()[index];
        }
        try {
            return br.com.contadoresassociados.folhas.server.db.Sql.fromPascal(PersonTypeModel.class, v);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("request.invalid", "Tipo de pessoa inválido.");
        }
    }
}
