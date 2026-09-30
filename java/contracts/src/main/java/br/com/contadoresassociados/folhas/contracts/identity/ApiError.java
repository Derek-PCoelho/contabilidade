package br.com.contadoresassociados.folhas.contracts.identity;

import java.util.List;

/** Erro de API com código estável (pendência 4.17). */
public record ApiError(
        String code,
        String message,
        List<String> details) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
