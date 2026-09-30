package br.com.contadoresassociados.folhas.application.dispatch;

public final class DispatchWorkflowException extends RuntimeException {

    private final String code;

    public DispatchWorkflowException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
