package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.time.OffsetDateTime;
import java.util.List;

public record DispatchReportResult(
        String xlsxPath,
        List<String> csvPaths,
        int itemCount,
        OffsetDateTime exportedAtUtc,
        String pdfPath) {

    public DispatchReportResult {
        csvPaths = csvPaths == null ? List.of() : List.copyOf(csvPaths);
    }
}
