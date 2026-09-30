package br.com.contadoresassociados.folhas.application.common;

/** Operação cancelada pelo usuário ou por timeout. */
public final class OperationCancelledException extends RuntimeException {
    public OperationCancelledException(String message) {
        super(message);
    }
}
