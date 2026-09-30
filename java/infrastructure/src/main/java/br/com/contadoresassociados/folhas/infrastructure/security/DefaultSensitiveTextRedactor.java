package br.com.contadoresassociados.folhas.infrastructure.security;

import br.com.contadoresassociados.folhas.application.security.SensitiveTextRedactor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redator central (pendência 7.4). Além do que a versão .NET cobria (bearer/senha, JWT, e-mail,
 * sequências de 10–14 dígitos), redige: CNPJ alfanumérico com ou sem pontuação, CPF/CNPJ/PIS sem
 * pontuação dentro de palavras, segredos em JSON/query ({@code "access_token":"…"}, {@code ?code=…}),
 * chaves de API longas e valores monetários em reais.
 */
public final class DefaultSensitiveTextRedactor implements SensitiveTextRedactor {

    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\"?(?:bearer|access[_ -]?token|refresh[_ -]?token|id[_ -]?token|client[_ -]?secret|password|senha|"
                    + "authorization|api[_ -]?key|code|code_verifier)\"?\\s*[:=]\\s*\"?)[^\\s,;&\"]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]{10,})?");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b([A-Z0-9._%+-])[A-Z0-9._%+-]*@([A-Z0-9.-]+\\.[A-Z]{2,})\\b");
    private static final Pattern CNPJ_ALNUM = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z0-9]{2}\\.?[A-Z0-9]{3}\\.?[A-Z0-9]{3}/?[A-Z0-9]{4}-?\\d{2}(?![A-Z0-9])");
    private static final Pattern TAX_ID = Pattern.compile("(?<!\\d)(?:\\d[.\\/-]?){10,14}(?!\\d)");
    private static final Pattern MONEY = Pattern.compile("(?i)R\\$\\s?-?\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?|R\\$\\s?-?\\d+(?:,\\d{2})?");
    private static final Pattern LONG_SECRET = Pattern.compile("\\b(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{40,}\\b");

    @Override
    public String redact(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var r = BEARER.matcher(value).replaceAll("$1[SEGREDO REDIGIDO]");
        r = SECRET_PAIR.matcher(r).replaceAll(m -> Matcher.quoteReplacement(m.group(1)) + "[SEGREDO REDIGIDO]");
        r = JWT.matcher(r).replaceAll("[TOKEN REDIGIDO]");
        r = EMAIL.matcher(r).replaceAll("$1***@$2");
        r = MONEY.matcher(r).replaceAll("[VALOR REDIGIDO]");
        r = CNPJ_ALNUM.matcher(r).replaceAll(m -> looksLikeCnpj(m.group()) ? "[DOCUMENTO FISCAL REDIGIDO]"
                : Matcher.quoteReplacement(m.group()));
        r = TAX_ID.matcher(r).replaceAll("[DOCUMENTO FISCAL REDIGIDO]");
        r = LONG_SECRET.matcher(r).replaceAll("[SEGREDO REDIGIDO]");
        r = r.replaceAll("[\\r\\n]+", " ");
        return r.length() <= maximumLength ? r : r.substring(0, Math.max(0, maximumLength));
    }

    /** Evita redigir palavras comuns de 14 letras: exige ao menos 2 dígitos nos 12 primeiros caracteres. */
    private static boolean looksLikeCnpj(String candidate) {
        var clean = candidate.replaceAll("[./-]", "");
        return clean.length() == 14 && clean.substring(0, 12).chars().filter(Character::isDigit).count() >= 2;
    }
}
