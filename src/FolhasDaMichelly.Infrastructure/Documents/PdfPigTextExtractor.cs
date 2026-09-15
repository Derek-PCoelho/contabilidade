using FolhasDaMichelly.Application.Documents;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class PdfPigTextExtractor : IPdfTextExtractor
{
    public Task<PdfTextExtraction> ExtractAsync(
        Stream pdfStream,
        DocumentRecognitionOptions options,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(pdfStream);
        ArgumentNullException.ThrowIfNull(options);
        cancellationToken.ThrowIfCancellationRequested();

        try
        {
            using var document = PdfDocument.Open(pdfStream);
            if (document.NumberOfPages > options.MaximumPageCount)
            {
                throw new DocumentImportException(
                    "document.page_limit_exceeded",
                    $"O PDF excede o limite de {options.MaximumPageCount} páginas.");
            }

            var pages = new List<ExtractedPdfPage>(document.NumberOfPages);
            var extractedCharacterCount = 0;
            foreach (var page in document.GetPages())
            {
                cancellationToken.ThrowIfCancellationRequested();
                var text = ContentOrderTextExtractor.GetText(page);
                extractedCharacterCount += text.Length;
                if (extractedCharacterCount > options.MaximumExtractedCharacters)
                {
                    throw new DocumentImportException(
                        "document.text_limit_exceeded",
                        "O conteúdo textual do PDF excede o limite de segurança.");
                }

                var words = page.GetWords().Select(word => new PdfWord(
                    word.Text,
                    page.Number,
                    (decimal)word.BoundingBox.Left,
                    (decimal)word.BoundingBox.Bottom,
                    (decimal)word.BoundingBox.Width,
                    (decimal)word.BoundingBox.Height)).ToArray();
                pages.Add(new ExtractedPdfPage(page.Number, text, words));
            }

            return Task.FromResult(new PdfTextExtraction(document.NumberOfPages, pages));
        }
        catch (DocumentImportException)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception exception)
        {
            throw new DocumentImportException(
                "document.pdf_invalid_or_protected",
                "O PDF está corrompido, protegido por senha ou usa uma estrutura não suportada.",
                exception);
        }
    }
}
