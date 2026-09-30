package br.com.contadoresassociados.folhas.server.sync;

import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncRequest;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandResult;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandStatus;
import br.com.contadoresassociados.folhas.contracts.sync.SyncNotification;
import br.com.contadoresassociados.folhas.server.realtime.SyncNotifier;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code api/sync/clients}: pull por checkpoint e push de 1 a 100 comandos. */
@RestController
@RequestMapping("/api/sync/clients")
public class SyncController {

    private final SyncRepository repository;
    private final SyncNotifier notifier;

    public SyncController(SyncRepository repository, SyncNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @GetMapping
    @Endpoint(permissions = "clients.read", rate = Policy.READ)
    public PullSyncResponse pull(HttpServletRequest http, @RequestParam(defaultValue = "0") long checkpoint) {
        return repository.pull(RequestContext.require(http).organizationId(), Math.max(0, checkpoint));
    }

    @PostMapping
    @Endpoint(permissions = "clients.write", mfa = true, rate = Policy.WRITE)
    public PushSyncResponse push(HttpServletRequest http, @RequestBody PushSyncRequest request) {
        if (request == null || request.commands().isEmpty()
                || request.commands().size() > PushSyncRequest.MAXIMUM_COMMANDS) {
            throw ApiException.badRequest("SYNC_TOO_MANY_COMMANDS",
                    "Envie entre 1 e " + PushSyncRequest.MAXIMUM_COMMANDS + " alterações por vez.");
        }
        var user = RequestContext.require(http);
        var results = new ArrayList<SyncCommandResult>(request.commands().size());
        for (var command : request.commands()) {
            results.add(repository.apply(user.organizationId(), user.userId(), user.deviceSessionId(), command));
        }
        if (results.stream().anyMatch(r -> r.status() == SyncCommandStatus.APPLIED)) {
            notifier.publish(user.organizationId(),
                    new SyncNotification(repository.latestCheckpoint(user.organizationId()), "client"));
        }
        return new PushSyncResponse(results);
    }
}
