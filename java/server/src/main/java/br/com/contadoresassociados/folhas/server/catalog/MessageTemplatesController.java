package br.com.contadoresassociados.folhas.server.catalog;

import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.sync.SyncNotification;
import br.com.contadoresassociados.folhas.server.realtime.SyncNotifier;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

/** {@code api/message-templates} — rotas do .NET mais status e arquivamento (4.19). */
@RestController
@RequestMapping("/api/message-templates")
public class MessageTemplatesController {

    private final CatalogRepository repository;
    private final SyncNotifier notifier;

    public MessageTemplatesController(CatalogRepository repository, SyncNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @GetMapping
    @Endpoint(permissions = "templates.read", rate = Policy.READ)
    public List<MessageTemplateModel> list(HttpServletRequest http, @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return repository.templates(RequestContext.require(http).organizationId(), clientId, includeInactive);
    }

    @PostMapping
    @Endpoint(permissions = "templates.write", mfa = true, rate = Policy.WRITE)
    public ResponseEntity<MessageTemplateModel> create(HttpServletRequest http,
            @RequestBody MessageTemplateMutationRequest request) {
        var user = RequestContext.require(http);
        var written = repository.saveTemplate(ClientsController.actor(user), null, ClientsController.require(request),
                UUID.randomUUID());
        notify(user.organizationId(), written.checkpoint());
        return ResponseEntity.created(java.net.URI.create("/api/message-templates/" + written.value().id()))
                .body(written.value());
    }

    @PutMapping("/{templateId}")
    @Endpoint(permissions = "templates.write", mfa = true, rate = Policy.WRITE)
    public MessageTemplateModel update(HttpServletRequest http, @PathVariable UUID templateId,
            @RequestBody MessageTemplateMutationRequest request) {
        var user = RequestContext.require(http);
        var written = repository.saveTemplate(ClientsController.actor(user), templateId,
                ClientsController.require(request), UUID.randomUUID());
        notify(user.organizationId(), written.checkpoint());
        return written.value();
    }

    @PostMapping("/{templateId}/status")
    @Endpoint(permissions = "templates.write", mfa = true, rate = Policy.WRITE)
    public MessageTemplateModel status(HttpServletRequest http, @PathVariable UUID templateId,
            @RequestBody ClientsController.StatusRequest request) {
        var user = RequestContext.require(http);
        var body = ClientsController.require(request);
        var written = repository.setTemplateActive(ClientsController.actor(user), templateId, body.expectedVersion(),
                body.isActive(), UUID.randomUUID());
        notify(user.organizationId(), written.checkpoint());
        return written.value();
    }

    @PostMapping("/{templateId}/archive")
    @Endpoint(permissions = "templates.write", mfa = true, rate = Policy.WRITE)
    public ResponseEntity<Void> archive(HttpServletRequest http, @PathVariable UUID templateId,
            @RequestBody ArchiveRequest request) {
        var user = RequestContext.require(http);
        var written = repository.archiveTemplate(ClientsController.actor(user), templateId,
                ClientsController.require(request), UUID.randomUUID());
        notify(user.organizationId(), written.checkpoint());
        return ResponseEntity.noContent().build();
    }

    private void notify(UUID organizationId, long checkpoint) {
        if (checkpoint > 0) {
            notifier.publish(organizationId, new SyncNotification(checkpoint, "message-template"));
        }
    }
}
