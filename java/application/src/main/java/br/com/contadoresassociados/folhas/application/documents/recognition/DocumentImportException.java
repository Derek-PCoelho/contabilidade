package br.com.contadoresassociados.folhas.application.documents.recognition;

public final class DocumentImportException extends RuntimeException {
    private final String code;

    public DocumentImportException(String code, String message) {
        this(code, message, null);
    }

    public DocumentImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
