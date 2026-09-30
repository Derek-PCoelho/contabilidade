package br.com.contadoresassociados.folhas.server.web;

import java.util.List;

/**
 * Erro de API com código estável (pendência 4.17): o Desktop traduz o código; a mensagem já vem
 * em português e nunca expõe detalhes internos.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;
    private final transient List<String> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "authentication_required", "Entre novamente para continuar.");
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }
}
