package br.com.contadoresassociados.folhas.application.sync;

import br.com.contadoresassociados.folhas.contracts.sync.ClientSyncItem;
import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncRequest;
import br.com.contadoresassociados.folhas.contracts.sync.PushSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.SyncCommandResult;
import br.com.contadoresassociados.folhas.contracts.sync.SyncNotification;
import br.com.contadoresassociados.folhas.contracts.sync.UpsertClientCommand;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Portas da sincronização (cliente local e servidor). */
public final class SyncPorts {

    private SyncPorts() {
    }

    public record PendingSyncOperation(UpsertClientCommand command, int attemptCount, OffsetDateTime nextAttemptAtUtc,
            boolean conflicted) {
    }

    /** Fila local (SQLite). */
    public interface LocalSyncStore {
        void initialize();

        void enqueue(UpsertClientCommand command);

        List<PendingSyncOperation> readyOperations(OffsetDateTime now);

        void markCompleted(UUID operationId);

        void markConflict(UUID operationId, ClientSyncItem current, String errorCode);

        void scheduleRetry(UUID operationId, OffsetDateTime nextAttemptAtUtc);

        long checkpoint();

        void applyPull(PullSyncResponse response);

        List<ClientSyncItem> clients();
    }

    /** Falha de rede/HTTP recuperável: o ciclo fica offline e agenda nova tentativa. */
    public static final class SyncTransportException extends RuntimeException {
        private final int statusCode;

        public SyncTransportException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public interface SyncTransport {
        PushSyncResponse push(PushSyncRequest request);

        PullSyncResponse pull(long checkpoint);
    }

    public interface SyncNotificationClient extends AutoCloseable {
        void start(Consumer<SyncNotification> onChange);

        void stop();

        @Override
        default void close() {
            stop();
        }
    }

    /** Servidor: persistência por organização. */
    public interface SyncRepository {
        SyncCommandResult apply(UUID organizationId, UUID userId, UpsertClientCommand command);

        PullSyncResponse pull(UUID organizationId, long checkpoint, int pageSize);

        long latestCheckpoint(UUID organizationId);
    }

    public interface SyncChangePublisher {
        void publish(UUID organizationId, SyncNotification notification);
    }
}
