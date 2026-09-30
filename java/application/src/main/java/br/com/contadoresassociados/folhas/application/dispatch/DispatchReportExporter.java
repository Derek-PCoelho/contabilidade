package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchReportResult;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import java.nio.file.Path;

public interface DispatchReportExporter {
    DispatchReportResult export(DispatchWorkspace dispatch, DocumentReviewWorkspace review, DispatchReportFilter filter,
            Path directory);
}
