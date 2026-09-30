package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.net.IDN;
import java.util.Locale;
import java.util.Optional;

/** Normalização estrita de e-mail (RFC 5321/5322 simplificada, com domínio IDN). */
public final class EmailAddress {

    private static final String ALLOWED_LOCAL_SYMBOLS = ".!#$%&'*+-/=?^_`{|}~";

    private EmailAddress() {
    }

    public static String normalize(String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 254) {
            throw invalid();
        }
        for (var i = 0; i < normalized.length(); i++) {
            var c = normalized.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw invalid();
            }
        }
        var separator = normalized.lastIndexOf('@');
        if (separator <= 0 || separator == normalized.length() - 1 || normalized.indexOf('@') != separator) {
            throw invalid();
        }
        var local = normalized.substring(0, separator);
        var domain = normalized.substring(separator + 1);
        if (local.length() > 64 || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            throw invalid();
        }
        for (var i = 0; i < local.length(); i++) {
            var c = local.charAt(i);
            if (!isAsciiLetterOrDigit(c) && ALLOWED_LOCAL_SYMBOLS.indexOf(c) < 0) {
                throw invalid();
            }
        }
        String ascii;
        try {
            ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        var labels = ascii.split("\\.", -1);
        if (ascii.length() > 253 || labels.length < 2 || labels[labels.length - 1].length() < 2) {
            throw invalid();
        }
        for (var label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                throw invalid();
            }
            for (var i = 0; i < label.length(); i++) {
                var c = label.charAt(i);
                if (!isAsciiLetterOrDigit(c) && c != '-') {
                    throw invalid();
                }
            }
        }
        return local.toLowerCase(Locale.ROOT) + "@" + ascii;
    }

    public static Optional<String> tryNormalize(String value) {
        try {
            return Optional.of(normalize(value));
        } catch (DomainValidationException e) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String value) {
        return tryNormalize(value).isPresent();
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static DomainValidationException invalid() {
        return new DomainValidationException("email.invalid",
                "Informe um e-mail válido, com endereço e domínio completos (ex.: financeiro@empresa.com.br).");
    }
}
