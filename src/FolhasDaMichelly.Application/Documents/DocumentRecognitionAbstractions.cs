using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public sealed class DocumentRecognitionOptions
{
    public const string SectionName = "DocumentRecognition";
    public const string EngineVersion = "phase4-v1";

    public long MaximumFileSizeBytes { get; set; } = 25 * 1024 * 1024;
    public int MaximumPageCount { get; set; } = 100;
    public int MaximumExtractedCharacters { get; set; } = 2_000_000;
    public TimeSpan ExtractionTimeout { get; set; } = TimeSpan.FromSeconds(20);
}

public sealed record PdfWord(
    string Text,
    int PageNumber,
    decimal X,
    decimal Y,
    decimal Width,
    decimal Height);

public sealed record ExtractedPdfPage(
    int PageNumber,
    string Text,
    IReadOnlyList<PdfWord> Words);

public sealed record PdfTextExtraction(
    int PageCount,
    IReadOnlyList<ExtractedPdfPage> Pages)
{
    public string FullText => string.Join("\n", Pages.Select(page => page.Text));
}

public sealed record DocumentClassification(
    RecognizedDocumentType DocumentType,
    string ProfileVersion,
    decimal Score,
    IReadOnlyList<string> MatchedAnchors);

public sealed record ParsedDocument(
    IReadOnlyList<RecognizedField> Fields,
    IReadOnlyList<RecognitionFinding> Findings);

public interface IPdfTextExtractor
{
    Task<PdfTextExtraction> ExtractAsync(
        Stream pdfStream,
        DocumentRecognitionOptions options,
        CancellationToken cancellationToken);
}

public interface IDocumentClassifier
{
    DocumentClassification Classify(PdfTextExtraction extraction);
}

public interface IDocumentParser
{
    ParsedDocument Parse(
        PdfTextExtraction extraction,
        DocumentClassification classification);
}

public interface IClientResolver
{
    Task<ClientResolutionResult> ResolveAsync(
        ClientResolutionRequest request,
        CancellationToken cancellationToken);
}

public interface IDocumentRecognitionCache
{
    Task<DocumentRecognitionResult?> GetAsync(
        string sha256,
        string engineVersion,
        CancellationToken cancellationToken);

    Task PutAsync(
        DocumentRecognitionResult result,
        string engineVersion,
        CancellationToken cancellationToken);
}

public interface IDocumentExtractor
{
    Task<DocumentRecognitionResult> RecognizeAsync(
        string filePath,
        CancellationToken cancellationToken);
}

public interface IDocumentRecognitionService : IDocumentExtractor;

public sealed class DocumentImportException(
    string code,
    string message,
    Exception? innerException = null) : Exception(message, innerException)
{
    public string Code { get; } = code;
}
