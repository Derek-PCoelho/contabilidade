package br.com.contadoresassociados.folhas.application.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Token de cancelamento cooperativo. Pendência 6.11: todas as operações longas
 * (salvar, despachar, backup, restauração) recebem um token cancelável.
 */
public final class CancellationToken {

    public static final CancellationToken NONE = new CancellationToken(null);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Instant deadline;

    private CancellationToken(Instant deadline) {
        this.deadline = deadline;
    }

    public static CancellationToken create() {
        return new CancellationToken(null);
    }

    public static CancellationToken withTimeout(Duration timeout) {
        return new CancellationToken(Instant.now().plus(timeout));
    }

    public void cancel() {
        if (this != NONE) {
            cancelled.set(true);
        }
    }

    public boolean isCancellationRequested() {
        return cancelled.get() || (deadline != null && Instant.now().isAfter(deadline));
    }

    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new OperationCancelledException("A operação foi cancelada.");
        }
    }
}
