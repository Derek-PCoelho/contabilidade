package br.com.contadoresassociados.folhas.application.documents;

/** Outra operação salvou o workspace antes (concorrência otimista). */
public final class WorkspaceConflictException extends RuntimeException {
    public WorkspaceConflictException(String scopeKey, long expected, long actual) {
        super("O espaço de trabalho foi alterado por outra operação (versão " + actual + ", esperada " + expected
                + "). Recarregue e tente de novo. Escopo: " + scopeKey);
    }
}
