package br.com.contadoresassociados.folhas.domain.common;

import java.util.UUID;

/** Versão esperada diferente da atual (concorrência otimista). */
public final class ConcurrencyConflictException extends RuntimeException {

    private final UUID entityId;
    private final long expectedVersion;
    private final long currentVersion;

    public ConcurrencyConflictException(UUID entityId, long expectedVersion, long currentVersion) {
        super("O registro " + entityId + " foi alterado por outra operação (versão esperada "
                + expectedVersion + ", atual " + currentVersion + ").");
        this.entityId = entityId;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public UUID entityId() {
        return entityId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
