package br.com.contadoresassociados.folhas.infrastructure.documents;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentImportException;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentRecognitionOptions;
import br.com.contadoresassociados.folhas.application.documents.recognition.PdfModel;
import br.com.contadoresassociados.folhas.application.documents.recognition.RecognitionPorts;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Extração com PDFBox. Mantém ordem de leitura por linha ({@code setSortByPosition}), coordenadas
 * de palavra com origem no canto inferior esquerdo (como o PdfPig), limites de páginas e caracteres e
 * cancelamento entre páginas. Memória limitada a 64 MB com transbordo em arquivo temporário.
 */
public final class PdfBoxTextExtractor implements RecognitionPorts.PdfTextExtractor {

    @Override
    public PdfModel.PdfTextExtraction extract(InputStream pdf, DocumentRecognitionOptions options, CancellationToken ct)
            throws IOException {
        var bytes = pdf.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, options.maximumFileSizeBytes() + 1));
        if (bytes.length > options.maximumFileSizeBytes()) {
            throw new DocumentImportException("document.size_invalid", "O PDF excede o tamanho máximo permitido.");
        }
        try (var doc = Loader.loadPDF(new RandomAccessReadBuffer(bytes), "",
                MemoryUsageSetting.setupMixed(64L * 1024 * 1024).streamCache)) {
            var pageCount = doc.getNumberOfPages();
            if (pageCount > options.maximumPageCount()) {
                throw new DocumentImportException("document.page_limit_exceeded",
                        "O PDF excede o limite de " + options.maximumPageCount() + " páginas.");
            }
            var pages = new ArrayList<PdfModel.ExtractedPdfPage>(pageCount);
            long total = 0;
            for (int number = 1; number <= pageCount; number++) {
                ct.throwIfCancellationRequested();
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Extração cancelada.");
                }
                var height = doc.getPage(number - 1).getMediaBox().getHeight();
                var collector = new WordCollector(number, height);
                collector.setSortByPosition(true);
                collector.setStartPage(number);
                collector.setEndPage(number);
                var text = collector.getText(doc);
                total += text.length();
                if (total > options.maximumExtractedCharacters()) {
                    throw new DocumentImportException("document.text_limit_exceeded",
                            "O conteúdo textual do PDF excede o limite de segurança.");
                }
                collector.flush();
                pages.add(new PdfModel.ExtractedPdfPage(number, text, collector.words));
            }
            return new PdfModel.PdfTextExtraction(pageCount, pages);
        } catch (InvalidPasswordException e) {
            throw new DocumentImportException("document.pdf_invalid_or_protected",
                    "O PDF está protegido por senha.", e);
        } catch (DocumentImportException | java.io.InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (e instanceof br.com.contadoresassociados.folhas.application.common.OperationCancelledException oce) {
                throw oce;
            }
            throw new DocumentImportException("document.pdf_invalid_or_protected",
                    "O PDF está corrompido, protegido por senha ou usa uma estrutura não suportada.", e);
        }
    }

    /** Agrupa glifos em palavras (separação por espaço ou salto horizontal). */
    private static final class WordCollector extends PDFTextStripper {
        final List<PdfModel.PdfWord> words = new ArrayList<>();
        private final int page;
        private final float pageHeight;
        private final StringBuilder current = new StringBuilder();
        private float x0;
        private float x1;
        private float yBottom;
        private float h;

        WordCollector(int page, float pageHeight) {
            this.page = page;
            this.pageHeight = pageHeight;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            for (var p : positions) {
                var ch = p.getUnicode();
                if (ch == null || ch.isBlank()) {
                    flush();
                    continue;
                }
                var gap = current.length() > 0 && (p.getXDirAdj() - x1) > p.getWidthOfSpace() * 0.5f;
                if (gap || current.length() > 0 && Math.abs(pageHeight - p.getYDirAdj() - yBottom) > p.getHeightDir()) {
                    flush();
                }
                if (current.length() == 0) {
                    x0 = p.getXDirAdj();
                    yBottom = pageHeight - p.getYDirAdj();
                    h = p.getHeightDir();
                }
                current.append(ch);
                x1 = p.getXDirAdj() + p.getWidthDirAdj();
                h = Math.max(h, p.getHeightDir());
            }
            flush();
            super.writeString(text, positions);
        }

        void flush() {
            if (current.length() == 0) {
                return;
            }
            words.add(new PdfModel.PdfWord(current.toString(), page, dec(x0), dec(yBottom), dec(x1 - x0), dec(h)));
            current.setLength(0);
        }

        private static BigDecimal dec(float v) {
            return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
        }
    }
}
