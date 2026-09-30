package br.com.contadoresassociados.folhas.application.documents;

public final class DocumentReviewException extends RuntimeException {

    private final String code;

    public DocumentReviewException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
