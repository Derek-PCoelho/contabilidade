package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.util.Locale;
import java.util.Optional;

/**
 * Normalização e validação de CPF e CNPJ.
 *
 * <p>Suporta o <b>CNPJ alfanumérico</b> (IN RFB nº 2.229/2024, vigente desde julho de 2026):
 * as 12 primeiras posições aceitam {@code [0-9A-Z]} e os 2 dígitos verificadores continuam
 * numéricos. O valor de cada caractere no módulo 11 é {@code código ASCII − 48}, portanto
 * CNPJs só com números continuam válidos exatamente como antes. Corrige a pendência 1.1 (A1).
 */
public final class BrazilianRegistration {

    public enum ValidationError { REQUIRED, UNSUPPORTED_CHARACTERS, INVALID_LENGTH, INVALID_CHECK_DIGITS }

    public static final int CPF_LENGTH = 11;
    public static final int CNPJ_LENGTH = 14;
    public static final int CNPJ_ROOT_LENGTH = 8;

    private static final int[] CPF_FIRST = {10, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CPF_SECOND = {11, 10, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CNPJ_FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CNPJ_SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private BrazilianRegistration() {
    }

    /** Resultado de uma normalização sem exceção. */
    public record Result(String normalized, ValidationError error) {
        public boolean isValid() {
            return error == null;
        }

        static Result ok(String value) {
            return new Result(value, null);
        }

        static Result fail(ValidationError error) {
            return new Result("", error);
        }
    }

    // ---------------------------------------------------------------- CPF

    public static Result tryNormalizeCpf(String value) {
        if (value != null && value.indexOf('/') >= 0) {
            return Result.fail(ValidationError.UNSUPPORTED_CHARACTERS);
        }
        var stripped = strip(value, false);
        if (!stripped.isValid()) {
            return stripped;
        }
        var digits = stripped.normalized();
        if (digits.length() != CPF_LENGTH) {
            return Result.fail(ValidationError.INVALID_LENGTH);
        }
        if (allSame(digits)
                || checkDigit(digits, 9, CPF_FIRST) != digits.charAt(9) - '0'
                || checkDigit(digits, 10, CPF_SECOND) != digits.charAt(10) - '0') {
            return Result.fail(ValidationError.INVALID_CHECK_DIGITS);
        }
        return Result.ok(digits);
    }

    public static String normalizeCpf(String value) {
        var result = tryNormalizeCpf(value);
        if (!result.isValid()) {
            throw error("CPF", CPF_LENGTH, result.error(), false);
        }
        return result.normalized();
    }

    public static boolean isValidCpf(String value) {
        return tryNormalizeCpf(value).isValid();
    }

    public static String formatCpf(String value) {
        var n = normalizeCpf(value);
        return n.substring(0, 3) + "." + n.substring(3, 6) + "." + n.substring(6, 9) + "-" + n.substring(9);
    }

    // ---------------------------------------------------------------- CNPJ

    public static Result tryNormalizeCnpj(String value) {
        var stripped = strip(value, true);
        if (!stripped.isValid()) {
            return stripped;
        }
        var chars = stripped.normalized();
        if (chars.length() != CNPJ_LENGTH) {
            return Result.fail(ValidationError.INVALID_LENGTH);
        }
        // Os dois últimos caracteres (DV) são sempre numéricos.
        if (!isAsciiDigit(chars.charAt(12)) || !isAsciiDigit(chars.charAt(13))) {
            return Result.fail(ValidationError.INVALID_CHECK_DIGITS);
        }
        if (allSame(chars)
                || checkDigit(chars, 12, CNPJ_FIRST) != chars.charAt(12) - '0'
                || checkDigit(chars, 13, CNPJ_SECOND) != chars.charAt(13) - '0') {
            return Result.fail(ValidationError.INVALID_CHECK_DIGITS);
        }
        return Result.ok(chars);
    }

    public static String normalizeCnpj(String value) {
        var result = tryNormalizeCnpj(value);
        if (!result.isValid()) {
            throw error("CNPJ", CNPJ_LENGTH, result.error(), true);
        }
        return result.normalized();
    }

    public static boolean isValidCnpj(String value) {
        return tryNormalizeCnpj(value).isValid();
    }

    /** Raiz (8 primeiras posições) de um CNPJ válido, possivelmente alfanumérica. */
    public static String cnpjRoot(String cnpj) {
        return normalizeCnpj(cnpj).substring(0, CNPJ_ROOT_LENGTH);
    }

    /** Normaliza uma raiz de CNPJ informada isoladamente (8 posições alfanuméricas). */
    public static String normalizeCnpjRoot(String value) {
        var stripped = strip(value, true);
        if (!stripped.isValid()) {
            throw new DomainValidationException("cnpj_root.unsupported_characters",
                    "A raiz do CNPJ aceita somente letras, números e os sinais '.', '/' e '-'.");
        }
        if (stripped.normalized().length() != CNPJ_ROOT_LENGTH) {
            throw new DomainValidationException("cnpj_root.invalid_length",
                    "A raiz do CNPJ deve ter exatamente 8 posições.");
        }
        return stripped.normalized();
    }

    public static String formatCnpj(String value) {
        var n = normalizeCnpj(value);
        return n.substring(0, 2) + "." + n.substring(2, 5) + "." + n.substring(5, 8) + "/"
                + n.substring(8, 12) + "-" + n.substring(12);
    }

    /** Formata CPF (11) ou CNPJ (14) já normalizado; devolve o valor original se não reconhecer. */
    public static String formatTaxId(String normalized) {
        if (normalized == null) {
            return "";
        }
        if (normalized.length() == CPF_LENGTH && isValidCpf(normalized)) {
            return formatCpf(normalized);
        }
        if (normalized.length() == CNPJ_LENGTH && isValidCnpj(normalized)) {
            return formatCnpj(normalized);
        }
        return normalized;
    }

    /** Máscara LGPD que mantém só os dois últimos caracteres. */
    public static String mask(String normalizedTaxId) {
        if (normalizedTaxId == null) {
            throw new DomainValidationException("tax_id.unsupported_length", "Identificador fiscal ausente.");
        }
        return switch (normalizedTaxId.length()) {
            case CPF_LENGTH -> "***.***.***-" + normalizedTaxId.substring(9);
            case CNPJ_LENGTH -> "**.***.***/****-" + normalizedTaxId.substring(12);
            default -> throw new DomainValidationException("tax_id.unsupported_length",
                    "Identificador fiscal com tamanho não suportado.");
        };
    }

    /** Detecta o tipo pelo tamanho normalizado. */
    public static Optional<PersonType> detectPersonType(String value) {
        if (tryNormalizeCpf(value).isValid()) {
            return Optional.of(PersonType.INDIVIDUAL);
        }
        if (tryNormalizeCnpj(value).isValid()) {
            return Optional.of(PersonType.LEGAL_ENTITY);
        }
        return Optional.empty();
    }

    /**
     * Remove sinais de formatação e espaços. Com {@code allowLetters}, letras viram maiúsculas.
     * Retorna erro para qualquer outro caractere.
     */
    static Result strip(String value, boolean allowLetters) {
        if (value == null || value.isBlank()) {
            return Result.fail(ValidationError.REQUIRED);
        }
        var builder = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (isAsciiDigit(c)) {
                builder.append(c);
            } else if (allowLetters && isAsciiLetter(c)) {
                builder.append(Character.toUpperCase(c));
            } else if (c == '.' || c == '/' || c == '-' || Character.isWhitespace(c)) {
                continue;
            } else {
                return Result.fail(ValidationError.UNSUPPORTED_CHARACTERS);
            }
        }
        return Result.ok(builder.toString().toUpperCase(Locale.ROOT));
    }

    /** Caracteres possíveis num CNPJ alfanumérico (útil para a UI filtrar entrada). */
    public static boolean isCnpjCharacter(char c) {
        return isAsciiDigit(c) || isAsciiLetter(c);
    }

    private static int checkDigit(String value, int length, int[] weights) {
        var sum = 0;
        for (var i = 0; i < length; i++) {
            sum += (value.charAt(i) - '0') * weights[i];
        }
        var remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static boolean allSame(String value) {
        for (var i = 1; i < value.length(); i++) {
            if (value.charAt(i) != value.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static DomainValidationException error(String field, int length, ValidationError error, boolean alnum) {
        var prefix = field.toLowerCase(Locale.ROOT) + ".";
        return switch (error) {
            case REQUIRED -> new DomainValidationException(prefix + "required", field + " é obrigatório.");
            case UNSUPPORTED_CHARACTERS -> new DomainValidationException(prefix + "unsupported_characters",
                    alnum
                            ? field + " aceita somente letras, números, espaços e os sinais '.', '/' e '-'."
                            : field + " aceita somente números, espaços e os sinais '.', '/' e '-'.");
            case INVALID_LENGTH -> new DomainValidationException(prefix + "invalid_length",
                    alnum
                            ? field + " deve conter exatamente " + length + " posições (letras ou números)."
                            : field + " deve conter exatamente " + length + " números.");
            case INVALID_CHECK_DIGITS -> new DomainValidationException(prefix + "invalid_check_digits",
                    field + " possui dígitos verificadores inválidos.");
        };
    }
}
