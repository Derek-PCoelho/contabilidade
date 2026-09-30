package br.com.contadoresassociados.folhas.domain.common;

import java.util.UUID;

/** Validações de texto e identificadores reutilizadas pelos agregados. */
public final class Guards {

    public static final UUID EMPTY = new UUID(0L, 0L);

    private Guards() {
    }

    public static String required(String value, int maximumLength, String code, String fieldLabel) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new DomainValidationException(code,
                    fieldLabel + " deve ter entre 1 e " + maximumLength + " caracteres.");
        }
        return normalized;
    }

    public static String optional(String value, int maximumLength, String code, String fieldLabel) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength) {
            throw new DomainValidationException(code,
                    fieldLabel + " não pode passar de " + maximumLength + " caracteres.");
        }
        return normalized;
    }

    public static boolean isEmpty(UUID id) {
        return id == null || EMPTY.equals(id);
    }

    public static UUID normalizeOptionalId(UUID id) {
        return isEmpty(id) ? null : id;
    }

    public static void requireIds(String code, String message, UUID... ids) {
        for (var id : ids) {
            if (isEmpty(id)) {
                throw new DomainValidationException(code, message);
            }
        }
    }
}
