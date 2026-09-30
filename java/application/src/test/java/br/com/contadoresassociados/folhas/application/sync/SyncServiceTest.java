package br.com.contadoresassociados.folhas.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.sync.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncServiceTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    static final class MemoryLocal implements SyncPorts.LocalSyncStore {
        final Map<UUID, SyncPorts.PendingSyncOperation> queue = new HashMap<>();
        final List<UUID> completed = new ArrayList<>();
        final List<UUID> conflicted = new ArrayList<>();
        final Map<UUID, OffsetDateTime> retries = new HashMap<>();
        final List<ClientSyncItem> clients = new ArrayList<>();
        long checkpoint;

        @Override public void initialize() { }
        @Override public void enqueue(UpsertClientCommand c) {
            queue.put(c.operationId(), new SyncPorts.PendingSyncOperation(c, 0, null, false));
        }
        @Override public List<SyncPorts.PendingSyncOperation> readyOperations(OffsetDateTime now) {
            return queue.values().stream().filter(o -> !completed.contains(o.command().operationId())
                    && !conflicted.contains(o.command().operationId())).toList();
        }
        @Override public void markCompleted(UUID id) { completed.add(id); }
        @Override public void markConflict(UUID id, ClientSyncItem current, String code) { conflicted.add(id); }
        @Override public void scheduleRetry(UUID id, OffsetDateTime next) { retries.put(id, next); }
        @Override public long checkpoint() { return checkpoint; }
        @Override public void applyPull(PullSyncResponse r) { clients.addAll(r.clients()); checkpoint = r.checkpoint(); }
        @Override public List<ClientSyncItem> clients() { return clients; }
    }

    static UpsertClientCommand cmd() {
        return new UpsertClientCommand(UUID.randomUUID(), UUID.randomUUID(), "Cliente", true, 0);
    }

    @Test
    void pushIsPagedInBatchesOfAtMost100() {
        var local = new MemoryLocal();
        for (int i = 0; i < 250; i++) {
            local.enqueue(cmd());
        }
        var sizes = new ArrayList<Integer>();
        var transport = new SyncPorts.SyncTransport() {
            @Override public PushSyncResponse push(PushSyncRequest r) {
                sizes.add(r.commands().size());
                return new PushSyncResponse(r.commands().stream().map(c -> new SyncCommandResult(c.operationId(),
                        SyncCommandStatus.APPLIED, null, null)).toList());
            }
            @Override public PullSyncResponse pull(long cp) { return new PullSyncResponse(cp, List.of(), false); }
        };
        var result = new SyncService(local, transport, Clock.fixed(NOW)).synchronize(CancellationToken.NONE);
        assertThat(sizes).containsExactly(100, 100, 50);
        assertThat(result.commandsApplied()).isEqualTo(250);
        assertThat(result.status()).isEqualTo(SyncService.Status.SYNCHRONIZED);
    }

    @Test
    void pullLoopsWhileHasMoreAndOfflineSchedulesRemaining() {
        var local = new MemoryLocal();
        var transport = new SyncPorts.SyncTransport() {
            @Override public PushSyncResponse push(PushSyncRequest r) {
                throw new SyncPorts.SyncTransportException("offline", 0, null);
            }
            @Override public PullSyncResponse pull(long cp) {
                var item = new ClientSyncItem(UUID.randomUUID(), "C" + cp, true, 1, NOW.atOffset(java.time.ZoneOffset.UTC));
                return new PullSyncResponse(cp + 1, List.of(item), cp < 2);
            }
        };
        var svc = new SyncService(local, transport, Clock.fixed(NOW));
        var result = svc.synchronize(CancellationToken.NONE);
        assertThat(result.checkpoint()).isEqualTo(3);
        assertThat(result.cachedClients()).isEqualTo(3);

        local.enqueue(cmd());
        result = svc.synchronize(CancellationToken.NONE);
        assertThat(result.status()).isEqualTo(SyncService.Status.OFFLINE);
        assertThat(local.retries).hasSize(1);
    }

    @Test
    void unansweredOperationsAreRetriedAndRejectedCountedSeparately() {
        var local = new MemoryLocal();
        var a = cmd();
        var b = cmd();
        var c = cmd();
        List.of(a, b, c).forEach(local::enqueue);
        var transport = new SyncPorts.SyncTransport() {
            @Override public PushSyncResponse push(PushSyncRequest r) {
                return new PushSyncResponse(List.of(new SyncCommandResult(a.operationId(), SyncCommandStatus.CONFLICT,
                        null, SyncErrorCodes.CONFLICT), new SyncCommandResult(b.operationId(),
                        SyncCommandStatus.REJECTED, null, SyncErrorCodes.INVALID_COMMAND)));
            }
            @Override public PullSyncResponse pull(long cp) { return new PullSyncResponse(cp, List.of(), false); }
        };
        var result = new SyncService(local, transport, Clock.fixed(NOW)).synchronize(CancellationToken.NONE);
        assertThat(result.conflicts()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(SyncService.Status.CONFLICT);
        assertThat(local.retries).containsOnlyKeys(c.operationId());
    }

    @Test
    void backoffIsCappedAtFiveMinutes() {
        assertThat(SyncService.backoff(20, UUID.randomUUID()).toSeconds()).isLessThanOrEqualTo(300 + 1);
        assertThat(SyncService.backoff(0, new UUID(0, 0)).toMillis()).isEqualTo(1000);
    }
}
