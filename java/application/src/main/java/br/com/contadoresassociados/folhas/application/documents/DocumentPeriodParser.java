package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriod;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriodKind;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normaliza a competência/período a partir dos campos reconhecidos.
 *
 * <p>Pendência 2.9 (competência só numérica): também entende nomes de mês ("MARÇO/2026",
 * "mar/2026", "Março de 2026").
 */
public final class DocumentPeriodParser {

    private static final Pattern COMPETENCE = Pattern.compile("^(0?[1-9]|1[0-2])[/\\-](\\d{4})$");
    private static final Pattern NAMED_COMPETENCE = Pattern.compile(
            "^([\\p{L}]{3,9})\\.?\\s*(?:/|-|de)?\\s*(\\d{4})$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4}");
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT));
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("janeiro", 1), Map.entry("fev", 2), Map.entry("fevereiro", 2),
            Map.entry("mar", 3), Map.entry("marco", 3), Map.entry("abr", 4), Map.entry("abril", 4),
            Map.entry("mai", 5), Map.entry("maio", 5), Map.entry("jun", 6), Map.entry("junho", 6),
            Map.entry("jul", 7), Map.entry("julho", 7), Map.entry("ago", 8), Map.entry("agosto", 8),
            Map.entry("set", 9), Map.entry("setembro", 9), Map.entry("out", 10), Map.entry("outubro", 10),
            Map.entry("nov", 11), Map.entry("novembro", 11), Map.entry("dez", 12), Map.entry("dezembro", 12));

    public DocumentPeriod parse(List<RecognizedField> fields) {
        var due = parseDate(value(fields, SemanticFieldRole.DUE_DATE)).orElse(null);

        var vacation = value(fields, SemanticFieldRole.VACATION_PERIOD);
        var vacationRange = parseRange(vacation);
        if (vacationRange != null) {
            return new DocumentPeriod(DocumentPeriodKind.DATE_RANGE, null, null, vacationRange[0], vacationRange[1],
                    due, vacation);
        }
        var eventText = value(fields, SemanticFieldRole.EVENT_DATE);
        var event = parseDate(eventText);
        if (event.isPresent()) {
            return new DocumentPeriod(DocumentPeriodKind.EVENT_DATE, null, null, event.get(), event.get(), due,
                    eventText);
        }
        var competence = value(fields, SemanticFieldRole.COMPETENCE);
        if (competence != null) {
            var parsed = parseCompetence(competence);
            if (parsed != null) {
                return new DocumentPeriod(parsed[0] == null ? DocumentPeriodKind.ANNUAL : DocumentPeriodKind.MONTHLY,
                        parsed[0], parsed[1], null, null, due, competence);
            }
        }
        var assessment = value(fields, SemanticFieldRole.ASSESSMENT_PERIOD);
        if (assessment != null) {
            var range = parseRange(assessment);
            if (range != null) {
                return new DocumentPeriod(DocumentPeriodKind.ASSESSMENT_PERIOD, null, null, range[0], range[1], due,
                        assessment);
            }
            var single = parseDate(assessment);
            if (single.isPresent()) {
                return new DocumentPeriod(DocumentPeriodKind.ASSESSMENT_PERIOD, null, null, single.get(), null, due,
                        assessment);
            }
        }
        var original = competence != null ? competence : assessment != null ? assessment
                : vacation != null ? vacation : eventText;
        return DocumentPeriod.unknown(original, due);
    }

    static Integer[] parseCompetence(String raw) {
        var value = raw.strip();
        var m = COMPETENCE.matcher(value);
        if (m.matches()) {
            return new Integer[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        var named = NAMED_COMPETENCE.matcher(value);
        if (named.matches()) {
            var key = java.text.Normalizer.normalize(named.group(1), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
            var month = MONTHS.get(key);
            if (month != null) {
                return new Integer[] {month, Integer.parseInt(named.group(2))};
            }
        }
        if (YEAR.matcher(value).matches()) {
            return new Integer[] {null, Integer.parseInt(value)};
        }
        return null;
    }

    private static LocalDate[] parseRange(String value) {
        if (value == null) {
            return null;
        }
        var m = DATE.matcher(value);
        var dates = new java.util.ArrayList<LocalDate>();
        while (m.find() && dates.size() < 2) {
            parseDate(m.group()).ifPresent(dates::add);
        }
        if (dates.size() == 2 && !dates.get(1).isBefore(dates.get(0))) {
            return new LocalDate[] {dates.get(0), dates.get(1)};
        }
        return null;
    }

    static Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (var f : FORMATS) {
            try {
                return Optional.of(LocalDate.parse(value.strip(), f));
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return Optional.empty();
    }

    private static String value(List<RecognizedField> fields, SemanticFieldRole role) {
        return fields.stream().filter(f -> f.role() == role).map(RecognizedField::value).findFirst().orElse(null);
    }
}
