package br.com.contadoresassociados.folhas.application.security;

/** Redator central: todo texto que vai para log, erro persistido ou relatório passa por aqui. */
public interface SensitiveTextRedactor {

    String redact(String value, int maximumLength);

    default String redact(String value) {
        return redact(value, 2_000);
    }
}
