package br.com.contadoresassociados.folhas.application.documents.recognition;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentRecognitionResult;
import java.io.InputStream;
import java.util.Optional;

/** Portas do reconhecimento, implementadas na Infrastructure (PDFBox, classificador por perfil, cache SQLite). */
public final class RecognitionPorts {

    private RecognitionPorts() {
    }

    public interface PdfTextExtractor {
        PdfModel.PdfTextExtraction extract(InputStream pdf, DocumentRecognitionOptions options, CancellationToken ct)
                throws java.io.IOException;
    }

    public interface DocumentClassifier {
        PdfModel.DocumentClassification classify(PdfModel.PdfTextExtraction extraction);
    }

    public interface DocumentParser {
        PdfModel.ParsedDocument parse(PdfModel.PdfTextExtraction extraction,
                PdfModel.DocumentClassification classification);
    }

    public interface RecognitionCache {
        Optional<DocumentRecognitionResult> get(String sha256, String engineVersion);

        void put(DocumentRecognitionResult result, String engineVersion);
    }

    public interface DocumentExtractor {
        DocumentRecognitionResult recognize(java.nio.file.Path file, CancellationToken ct);
    }
}
