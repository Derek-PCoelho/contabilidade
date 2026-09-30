package br.com.contadoresassociados.folhas.desktop.state;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opções do seletor de competência (ano/mês) — mesmos rótulos da versão .NET. */
public final class OperationalPeriod {

    public static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private OperationalPeriod() {
    }

    public record YearOption(Integer year, String label) {
        public static final YearOption ALL = new YearOption(null, "Todos os anos");

        @Override
        public String toString() {
            return label;
        }
    }

    /** {@code month == 0} significa "Sem mês definido"; {@code null}, todos os meses. */
    public record MonthOption(Integer month, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static List<MonthOption> months() {
        var list = new ArrayList<MonthOption>();
        list.add(new MonthOption(null, "Todos os meses"));
        for (var month = 1; month <= 12; month++) {
            list.add(new MonthOption(month, monthLabel(month)));
        }
        list.add(new MonthOption(0, "Sem mês definido"));
        return List.copyOf(list);
    }

    public static List<YearOption> years(int currentYear) {
        var list = new ArrayList<YearOption>();
        list.add(YearOption.ALL);
        for (var year = currentYear + 1; year >= currentYear - 6; year--) {
            list.add(new YearOption(year, Integer.toString(year)));
        }
        return List.copyOf(list);
    }

    public static String monthLabel(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, PT_BR).toUpperCase(PT_BR);
    }

    /** Rótulo do cabeçalho "COMPETÊNCIA". */
    public static String label(Integer year, Integer month) {
        if (year == null && month == null) {
            return "Todos os períodos";
        }
        if (year == null) {
            return month == 0 ? "Sem mês definido · todos os anos" : monthLabel(month) + " · todos os anos";
        }
        if (month == null) {
            return "Todos os meses de " + year;
        }
        return month == 0 ? "Sem mês definido · " + year : monthLabel(month) + " " + year;
    }
}
