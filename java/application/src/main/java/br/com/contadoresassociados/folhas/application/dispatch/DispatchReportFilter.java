package br.com.contadoresassociados.folhas.application.dispatch;

import java.util.UUID;

/** Recorte de relatório por cliente e/ou período (mesmos escopos da versão .NET). */
public record DispatchReportFilter(Scope scope, Integer year, Integer month, UUID clientId, String clientDisplayName,
        Integer startYear, Integer startMonth, Integer endYear, Integer endMonth) {

    public enum Scope { ALL_PERIODS, MONTH, CLIENT, YEAR, RANGE }

    public static final DispatchReportFilter ALL = new DispatchReportFilter(Scope.ALL_PERIODS, null, null, null, null,
            null, null, null, null);

    public static DispatchReportFilter month(int year, int month) {
        return new DispatchReportFilter(Scope.MONTH, year, month, null, null, null, null, null, null);
    }

    public static DispatchReportFilter year(int year) {
        return new DispatchReportFilter(Scope.YEAR, year, null, null, null, null, null, null, null);
    }

    public static DispatchReportFilter range(int sy, int sm, int ey, int em) {
        return new DispatchReportFilter(Scope.RANGE, null, null, null, null, sy, sm, ey, em);
    }

    public static DispatchReportFilter client(UUID id, String name) {
        return new DispatchReportFilter(Scope.CLIENT, null, null, id, name, null, null, null, null);
    }

    public DispatchReportFilter withClient(UUID id, String name) {
        return new DispatchReportFilter(scope, year, month, id, name, startYear, startMonth, endYear, endMonth);
    }

    public boolean hasPeriod() {
        return scope != Scope.ALL_PERIODS && scope != Scope.CLIENT;
    }

    public boolean matches(Integer y, Integer m) {
        return switch (scope) {
            case ALL_PERIODS, CLIENT -> true;
            case MONTH -> y != null && m != null && y.equals(year) && m.equals(month);
            case YEAR -> y != null && y.equals(year);
            case RANGE -> y != null && m != null && key(y, m) >= key(startYear, startMonth)
                    && key(y, m) <= key(endYear, endMonth);
        };
    }

    public void validate() {
        if (scope == Scope.CLIENT && clientId == null) {
            throw new DispatchWorkflowException("REPORT_CLIENT_REQUIRED", "Escolha o cliente que deve aparecer no relatório.");
        }
        if (scope == Scope.MONTH && (!validYear(year) || month == null || month < 1 || month > 12)) {
            throw new DispatchWorkflowException("REPORT_MONTH_REQUIRED", "Escolha um ano e um mês válidos para este relatório.");
        }
        if (scope == Scope.YEAR && !validYear(year)) {
            throw new DispatchWorkflowException("REPORT_YEAR_REQUIRED", "Escolha um ano válido para este relatório.");
        }
        if (scope == Scope.RANGE) {
            if (!validYear(startYear) || !validYear(endYear) || startMonth == null || endMonth == null
                    || startMonth < 1 || startMonth > 12 || endMonth < 1 || endMonth > 12) {
                throw new DispatchWorkflowException("REPORT_RANGE_REQUIRED",
                        "Escolha competências inicial e final válidas para este relatório.");
            }
            if (key(startYear, startMonth) > key(endYear, endMonth)) {
                throw new DispatchWorkflowException("REPORT_RANGE_INVERTED",
                        "A competência inicial não pode ser posterior à competência final.");
            }
        }
    }

    private static boolean validYear(Integer y) {
        return y != null && y >= 1900 && y <= 9999;
    }

    private static int key(int y, int m) {
        return y * 12 + m;
    }
}
