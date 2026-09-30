package br.com.contadoresassociados.folhas.application.sync;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncRequest;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandResult;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ciclo de sincronização.
 *
 * <p>Correções: 2.5 — push em páginas de até {@value #MAXIMUM_PUSH_BATCH} comandos (limite do servidor);
 * pull em laço enquanto {@code hasMore}; operações sem resultado na resposta voltam para retentativa
 * (antes ficavam presas); REJECTED é contado à parte de CONFLICT; o checkpoint só avança quando a página
 * é aplicada.
 */
public final class SyncService {

    public static final int MAXIMUM_PUSH_BATCH = 100;
    static final int MAXIMUM_PULL_PAGES = 1_000;
    private static final Duration MAXIMUM_BACKOFF = Duration.ofMinutes(5);

    public enum Status { SYNCHRONIZED, OFFLINE, CONFLICT }

    public record CycleResult(Status status, int commandsApplied, int conflicts, int rejected, int pendingRetries,
            int cachedClients, long checkpoint) {
    }

    private final SyncPorts.LocalSyncStore local;
    private final SyncPorts.SyncTransport transport;
    private final Clock clock;

    public SyncService(SyncPorts.LocalSyncStore local, SyncPorts.SyncTransport transport, Clock clock) {
        this.local = local;
        this.transport = transport;
        this.clock = clock;
    }

    public synchronized CycleResult synchronize(CancellationToken ct) {
        local.initialize();
        var ready = local.readyOperations(clock.nowUtc());
        int applied = 0;
        int conflicts = 0;
        int rejected = 0;
        for (int start = 0; start < ready.size(); start += MAXIMUM_PUSH_BATCH) {
            ct.throwIfCancellationRequested();
            var page = ready.subList(start, Math.min(ready.size(), start + MAXIMUM_PUSH_BATCH));
            List<SyncCommandResult> results;
            try {
                results = transport.push(new PushSyncRequest(page.stream().map(SyncPorts.PendingSyncOperation::command)
                        .toList())).results();
            } catch (SyncPorts.SyncTransportException e) {
                var remaining = ready.subList(start, ready.size());
                scheduleRetries(remaining);
                return result(Status.OFFLINE, applied, conflicts, rejected, remaining.size());
            }
            Set<UUID> answered = new HashSet<>();
            for (var r : results == null ? List.<SyncCommandResult>of() : results) {
                answered.add(r.operationId());
                switch (r.status()) {
                    case APPLIED, DUPLICATE -> {
                        local.markCompleted(r.operationId());
                        applied++;
                    }
                    case CONFLICT -> {
                        local.markConflict(r.operationId(), r.current(), r.errorCode());
                        conflicts++;
                    }
                    case REJECTED -> {
                        local.markConflict(r.operationId(), r.current(), r.errorCode());
                        rejected++;
                    }
                }
            }
            var unanswered = page.stream().filter(o -> !answered.contains(o.command().operationId())).toList();
            scheduleRetries(unanswered);
        }

        try {
            var pages = 0;
            while (true) {
                ct.throwIfCancellationRequested();
                var checkpoint = local.checkpoint();
                var pulled = Objects.requireNonNull(transport.pull(checkpoint), "pull");
                local.applyPull(pulled);
                if (!pulled.hasMore() || pulled.checkpoint() <= checkpoint || ++pages >= MAXIMUM_PULL_PAGES) {
                    break;
                }
            }
        } catch (SyncPorts.SyncTransportException e) {
            return result(Status.OFFLINE, applied, conflicts, rejected, 0);
        }
        return result(conflicts + rejected > 0 ? Status.CONFLICT : Status.SYNCHRONIZED, applied, conflicts, rejected, 0);
    }

    private void scheduleRetries(List<SyncPorts.PendingSyncOperation> operations) {
        var now = clock.nowUtc();
        for (var op : operations) {
            local.scheduleRetry(op.command().operationId(), now.plus(backoff(op.attemptCount(), op.command().operationId())));
        }
    }

    static Duration backoff(int attempts, UUID operationId) {
        var exponent = Math.min(Math.max(attempts, 0), 8);
        var seconds = Math.min((long) Math.pow(2, exponent), MAXIMUM_BACKOFF.toSeconds());
        var jitter = operationId.hashCode() & 0x3FF;
        return Duration.ofSeconds(seconds).plusMillis(jitter);
    }

    private CycleResult result(Status status, int applied, int conflicts, int rejected, int pending) {
        return new CycleResult(status, applied, conflicts, rejected, pending, local.clients().size(), local.checkpoint());
    }
}
