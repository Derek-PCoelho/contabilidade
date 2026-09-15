using System.Security.Cryptography;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public sealed class DocumentRecognitionService(
    IPdfTextExtractor pdfTextExtractor,
    IDocumentClassifier classifier,
    IDocumentParser parser,
    IClientResolver clientResolver,
    IDocumentRecognitionCache cache,
    DocumentRecognitionOptions options) : IDocumentRecognitionService
{
    public async Task<DocumentRecognitionResult> RecognizeAsync(
        string filePath,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(filePath))
        {
            throw new DocumentImportException("document.path_required", "Selecione um arquivo PDF.");
        }

        var file = new FileInfo(filePath);
        if (!file.Exists)
        {
            throw new DocumentImportException("document.not_found", "O arquivo selecionado não existe.");
        }

        if (!string.Equals(file.Extension, ".pdf", StringComparison.OrdinalIgnoreCase))
        {
            throw new DocumentImportException("document.extension_not_pdf", "Somente arquivos PDF são aceitos.");
        }

        if (file.Length is <= 0 || file.Length > options.MaximumFileSizeBytes)
        {
            throw new DocumentImportException(
                "document.size_invalid",
                $"O PDF deve ter até {options.MaximumFileSizeBytes / 1024 / 1024} MB.");
        }

        await using var stream = new FileStream(
            file.FullName,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);

        var signature = new byte[5];
        var bytesRead = await stream.ReadAsync(signature, cancellationToken);
        if (bytesRead != signature.Length || !signature.AsSpan().SequenceEqual("%PDF-"u8))
        {
            throw new DocumentImportException(
                "document.mime_not_pdf",
                "O conteúdo do arquivo não possui assinatura MIME de PDF.");
        }

        stream.Position = 0;
        var hashBytes = await SHA256.HashDataAsync(stream, cancellationToken);
        var sha256 = Convert.ToHexStringLower(hashBytes);
        var cached = await cache.GetAsync(
            sha256,
            DocumentRecognitionOptions.EngineVersion,
            cancellationToken);
        if (cached is not null)
        {
            if (cached.NeedsOcr)
            {
                return cached with { FileName = file.Name, FromCache = true };
            }

            var refreshedResolution = await ResolveClientAsync(cached.Fields, cancellationToken);
            var refreshedScore = CalculateConfidence(
                cached.DocumentType == RecognizedDocumentType.Unclassified ? 0m : 1m,
                cached.Fields,
                refreshedResolution);
            return cached with
            {
                FileName = file.Name,
                FromCache = true,
                Resolution = refreshedResolution,
                ConfidenceScore = refreshedScore,
                Confidence = ToConfidence(refreshedScore),
            };
        }

        stream.Position = 0;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(options.ExtractionTimeout);

        PdfTextExtraction extraction;
        try
        {
            extraction = await Task.Run(
                    () => pdfTextExtractor.ExtractAsync(stream, options, timeout.Token),
                    CancellationToken.None);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new DocumentImportException(
                "document.extraction_timeout",
                "A extração excedeu o limite de tempo configurado.");
        }

        var hasText = extraction.Pages.Any(page => !string.IsNullOrWhiteSpace(page.Text));
        if (!hasText)
        {
            var ocrResult = new DocumentRecognitionResult(
                file.Name,
                sha256,
                "application/pdf",
                file.Length,
                extraction.PageCount,
                RecognizedDocumentType.Unclassified,
                DocumentRecognitionOptions.EngineVersion,
                RecognitionConfidence.Low,
                0m,
                true,
                false,
                [],
                ClientResolutionResult.Unresolved("document.needs_ocr"),
                [new RecognitionFinding(
                    "document.needs_ocr",
                    "O PDF não contém texto pesquisável; OCR manual será necessário em fase futura.",
                    true)]);
            await cache.PutAsync(ocrResult, DocumentRecognitionOptions.EngineVersion, cancellationToken);
            return ocrResult;
        }

        var classification = classifier.Classify(extraction);
        var parsed = parser.Parse(extraction, classification);
        var resolution = await ResolveClientAsync(parsed.Fields, cancellationToken);
        var score = CalculateConfidence(classification.Score, parsed.Fields, resolution);
        var result = new DocumentRecognitionResult(
            file.Name,
            sha256,
            "application/pdf",
            file.Length,
            extraction.PageCount,
            classification.DocumentType,
            classification.ProfileVersion,
            ToConfidence(score),
            score,
            false,
            false,
            parsed.Fields,
            resolution,
            parsed.Findings);
        await cache.PutAsync(result, DocumentRecognitionOptions.EngineVersion, cancellationToken);
        return result;
    }

    private async Task<ClientResolutionResult> ResolveClientAsync(
        IReadOnlyList<RecognizedField> fields,
        CancellationToken cancellationToken)
    {
        var resolutionFields = fields
            .Where(field => field.Role is
                SemanticFieldRole.EmployerTaxId or
                SemanticFieldRole.ClientTaxId or
                SemanticFieldRole.EstablishmentTaxId or
                SemanticFieldRole.EmployerName or
                SemanticFieldRole.ClientName or
                SemanticFieldRole.InternalCode)
            .Select(field => field with
            {
                Evidence = field.Evidence with
                {
                    Snippet = $"{field.Name}: {field.DisplayValue}",
                },
            })
            .ToArray();
        return await clientResolver.ResolveAsync(
            new ClientResolutionRequest(resolutionFields),
            cancellationToken);
    }

    private static decimal CalculateConfidence(
        decimal classificationScore,
        IReadOnlyList<RecognizedField> fields,
        ClientResolutionResult resolution)
    {
        var fieldScore = fields.Count == 0 ? 0m : fields.Average(field => field.Confidence);
        var resolutionScore = resolution.IsResolved ? resolution.Confidence : 0m;
        return decimal.Round(
            Math.Clamp((classificationScore * .45m) + (fieldScore * .35m) + (resolutionScore * .20m), 0m, 1m),
            2);
    }

    private static RecognitionConfidence ToConfidence(decimal score) => score switch
    {
        >= .85m => RecognitionConfidence.High,
        >= .60m => RecognitionConfidence.Medium,
        _ => RecognitionConfidence.Low,
    };
}
