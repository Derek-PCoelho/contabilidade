package br.com.contadoresassociados.folhas.application.clients;

import java.util.UUID;

/** Erros do catálogo com código estável. */
public class CatalogException extends RuntimeException {

    private final String code;

    public CatalogException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class Concurrency extends CatalogException {
        public Concurrency(UUID entityId, long expected, long actual) {
            super("catalog.concurrency", "O cadastro foi alterado por outra pessoa (versão " + actual + ", esperada "
                    + expected + "). Recarregue antes de salvar. Registro: " + entityId);
        }
    }

    public static final class Duplicate extends CatalogException {
        public Duplicate(String field) {
            super("catalog.duplicate", "Já existe outro cadastro ativo com o mesmo " + field + ".");
        }
    }

    public static final class NotFound extends CatalogException {
        public NotFound(String what) {
            super("catalog.not_found", what + " não encontrado.");
        }
    }
}
