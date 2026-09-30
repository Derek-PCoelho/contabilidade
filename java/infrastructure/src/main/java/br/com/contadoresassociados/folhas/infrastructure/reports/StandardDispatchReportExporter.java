package br.com.contadoresassociados.folhas.infrastructure.reports;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchReportExporter;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchReportFilter;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchReportResult;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** XLSX (POI) + PDF (PDFBox) + 5 CSVs, com os mesmos nomes de arquivo da versão .NET. */
public final class StandardDispatchReportExporter implements DispatchReportExporter {

    private final Clock clock;
    private final ZoneId displayZone;

    public StandardDispatchReportExporter(Clock clock, ZoneId displayZone) {
        this.clock = clock;
        this.displayZone = displayZone;
    }

    @Override
    public DispatchReportResult export(DispatchWorkspace dispatch, DocumentReviewWorkspace review,
            DispatchReportFilter filter, Path directory) {
        try {
            Files.createDirectories(directory);
            var exportedAt = clock.nowUtc();
            var suffix = exportedAt.withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            var xlsx = directory.resolve("folhas-da-michelly-" + suffix + ".xlsx");
            var pdf = directory.resolve("folhas-da-michelly-" + suffix + ".pdf");
            var coverage = ReportRows.coverage(dispatch, review, filter);
            XlsxReportWriter.write(xlsx, dispatch, review, exportedAt, coverage);
            BrandedPdfReportWriter.write(pdf, dispatch, review, exportedAt, coverage, displayZone);
            var csvDir = Files.createDirectories(directory.resolve("folhas-da-michelly-" + suffix + "-csv"));
            var csvs = List.of(CsvWriter.write(csvDir, "Resumo.csv", ReportRows.summary(dispatch, review, exportedAt,
                    coverage)), CsvWriter.write(csvDir, "Itens.csv", ReportRows.items(dispatch, review)),
                    CsvWriter.write(csvDir, "Erros.csv", ReportRows.errors(dispatch, review)),
                    CsvWriter.write(csvDir, "Duplicados.csv", ReportRows.duplicates(review)),
                    CsvWriter.write(csvDir, "Auditoria.csv", ReportRows.audit(dispatch, review)));
            return new DispatchReportResult(xlsx.toString(), csvs.stream().map(Path::toString).toList(),
                    dispatch.items().size(), exportedAt, pdf.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar os relatórios na pasta escolhida.", e);
        }
    }
}
