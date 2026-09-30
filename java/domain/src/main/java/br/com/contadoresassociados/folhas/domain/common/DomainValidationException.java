package br.com.contadoresassociados.folhas.domain.common;

import java.util.Map;
import java.util.Objects;

/**
 * Violação de regra de domínio com código estável.
 *
 * <p>Corrige a pendência 1.4 do relatório: a versão .NET só tinha mensagens textuais em inglês,
 * e a UI traduzia por substring. Aqui o {@link #code()} é o contrato, e a mensagem já sai em pt-BR.
 */
public final class DomainValidationException extends RuntimeException {

    private final String code;
    private final Map<String, Object> parameters;

    public DomainValidationException(String code, String message) {
        this(code, message, Map.of());
    }

    public DomainValidationException(String code, String message, Map<String, Object> parameters) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.parameters = Map.copyOf(parameters);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }
}
