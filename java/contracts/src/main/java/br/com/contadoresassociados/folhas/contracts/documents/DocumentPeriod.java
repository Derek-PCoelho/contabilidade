package br.com.contadoresassociados.folhas.contracts.documents;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Período de um documento. Os rótulos usam formato fixo pt-BR, independente da cultura do
 * sistema operacional (pendência 1.6).
 */
public record DocumentPeriod(
        DocumentPeriodKind kind,
        Integer month,
        Integer year,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate dueDate,
        String originalText) {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    public DocumentPeriod {
        kind = kind == null ? DocumentPeriodKind.UNKNOWN : kind;
    }

    public static DocumentPeriod unknown(String originalText, LocalDate dueDate) {
        return new DocumentPeriod(DocumentPeriodKind.UNKNOWN, null, null, null, null, dueDate, originalText);
    }

    public static DocumentPeriod monthly(int month, int year) {
        return new DocumentPeriod(DocumentPeriodKind.MONTHLY, month, year, null, null, null, null);
    }

    @JsonIgnore
    public String canonicalKey() {
        return switch (kind) {
            case MONTHLY -> month != null && year != null ? "month:%04d-%02d".formatted(year, month) : fallback();
            case ANNUAL -> year != null ? "year:%04d".formatted(year) : fallback();
            case DATE_RANGE -> startDate != null && endDate != null
                    ? "range:" + ISO.format(startDate) + ":" + ISO.format(endDate) : fallback();
            case EVENT_DATE -> startDate != null ? "event:" + ISO.format(startDate) : fallback();
            case ASSESSMENT_PERIOD -> startDate == null ? fallback()
                    : endDate != null ? "assessment:" + ISO.format(startDate) + ":" + ISO.format(endDate)
                    : "assessment:" + ISO.format(startDate);
            case UNKNOWN -> fallback();
        };
    }

    @JsonIgnore
    public String groupingPeriodKey() {
        return switch (kind) {
            case MONTHLY -> month != null && year != null ? "%04d-%02d".formatted(year, month) : canonicalKey();
            case ASSESSMENT_PERIOD -> startDate != null
                    ? "%04d-%02d".formatted(startDate.getYear(), startDate.getMonthValue()) : canonicalKey();
            case ANNUAL -> year != null ? "%04d".formatted(year) : canonicalKey();
            case DATE_RANGE -> startDate != null && endDate != null
                    ? ISO.format(startDate) + "_" + ISO.format(endDate) : canonicalKey();
            case EVENT_DATE -> startDate != null ? ISO.format(startDate) : canonicalKey();
            case UNKNOWN -> canonicalKey();
        };
    }

    @JsonIgnore
    public String displayLabel() {
        return switch (kind) {
            case MONTHLY -> month != null && year != null ? "%02d/%04d".formatted(month, year) : unknownLabel();
            case ANNUAL -> year != null ? "%04d".formatted(year) : unknownLabel();
            case DATE_RANGE, ASSESSMENT_PERIOD -> startDate == null ? unknownLabel()
                    : endDate != null ? BR.format(startDate) + " a " + BR.format(endDate) : BR.format(startDate);
            case EVENT_DATE -> startDate != null ? BR.format(startDate) : unknownLabel();
            case UNKNOWN -> unknownLabel();
        };
    }

    /** Rótulo da competência mensal (mm/aaaa), usado para rotular grupos (pendência 2.8). */
    @JsonIgnore
    public String groupingLabel() {
        var key = groupingPeriodKey();
        if (key.matches("\\d{4}-\\d{2}")) {
            return key.substring(5) + "/" + key.substring(0, 4);
        }
        return displayLabel();
    }

    private String fallback() {
        return "unknown:" + (originalText == null ? "" : originalText.strip().toUpperCase(Locale.ROOT));
    }

    private String unknownLabel() {
        return originalText == null ? "Período não identificado" : originalText;
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.kind = kind;
        b.month = month;
        b.year = year;
        b.startDate = startDate;
        b.endDate = endDate;
        b.dueDate = dueDate;
        b.originalText = originalText;
        return b;
    }

    /** Cópia mutável para derivar novas versões (equivalente ao "with" do C#). */
    public static final class Builder {
        private DocumentPeriodKind kind;
        private Integer month;
        private Integer year;
        private LocalDate startDate;
        private LocalDate endDate;
        private LocalDate dueDate;
        private String originalText;

        public Builder kind(DocumentPeriodKind value) {
            this.kind = value;
            return this;
        }

        public Builder month(Integer value) {
            this.month = value;
            return this;
        }

        public Builder year(Integer value) {
            this.year = value;
            return this;
        }

        public Builder startDate(LocalDate value) {
            this.startDate = value;
            return this;
        }

        public Builder endDate(LocalDate value) {
            this.endDate = value;
            return this;
        }

        public Builder dueDate(LocalDate value) {
            this.dueDate = value;
            return this;
        }

        public Builder originalText(String value) {
            this.originalText = value;
            return this;
        }

        public DocumentPeriod build() {
            return new DocumentPeriod(kind, month, year, startDate, endDate, dueDate, originalText);
        }
    }
}
