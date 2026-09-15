using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase4DocumentRecognitionServiceTests
{
    [Fact]
    public async Task ContentSignatureIsValidatedBeforeExtraction()
    {
        var path = Path.Combine(Path.GetTempPath(), $"not-pdf-{Guid.NewGuid():N}.pdf");
        try
        {
            await File.WriteAllTextAsync(path, "not a pdf");
            var extractor = new RecordingExtractor(Extraction());
            var service = CreateService(extractor, new MemoryCache());

            var exception = await Assert.ThrowsAsync<DocumentImportException>(() =>
                service.RecognizeAsync(path, CancellationToken.None));

            Assert.Equal("document.mime_not_pdf", exception.Code);
            Assert.Equal(0, extractor.CallCount);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task SameHashUsesVersionedCacheAndDoesNotExtractTwice()
    {
        var path = await CreatePdfLikeFileAsync();
        try
        {
            var extractor = new RecordingExtractor(Extraction());
            var resolver = new CountingClientResolver();
            var service = CreateService(extractor, new MemoryCache(), resolver);

            var first = await service.RecognizeAsync(path, CancellationToken.None);
            var second = await service.RecognizeAsync(path, CancellationToken.None);

            Assert.False(first.FromCache);
            Assert.True(second.FromCache);
            Assert.Equal(first.Sha256, second.Sha256);
            Assert.Equal(1, extractor.CallCount);
            Assert.Equal(2, resolver.CallCount);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task EmptyTextIsMarkedNeedsOcrWithoutAutomaticOcr()
    {
        var path = await CreatePdfLikeFileAsync();
        try
        {
            var extractor = new RecordingExtractor(
                new PdfTextExtraction(1, [new ExtractedPdfPage(1, string.Empty, [])]));
            var service = CreateService(extractor, new MemoryCache());

            var result = await service.RecognizeAsync(path, CancellationToken.None);

            Assert.True(result.NeedsOcr);
            Assert.Equal(RecognizedDocumentType.Unclassified, result.DocumentType);
            Assert.Contains(result.Findings, finding => finding.Code == "document.needs_ocr" && finding.IsBlocker);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task ResolverReceivesOnlyEligibleClientFieldsWithRedactedEvidence()
    {
        var path = await CreatePdfLikeFileAsync();
        try
        {
            var resolver = new CapturingClientResolver();
            var service = new DocumentRecognitionService(
                new RecordingExtractor(Extraction()),
                new FixedClassifier(),
                new ResolverPrivacyParser(),
                resolver,
                new MemoryCache(),
                new DocumentRecognitionOptions());

            await service.RecognizeAsync(path, CancellationToken.None);

            var request = Assert.IsType<ClientResolutionRequest>(resolver.Request);
            var field = Assert.Single(request.Fields);
            Assert.Equal(SemanticFieldRole.EmployerTaxId, field.Role);
            Assert.DoesNotContain("11222333000181", field.Evidence.Snippet, StringComparison.Ordinal);
        }
        finally
        {
            File.Delete(path);
        }
    }

    private static DocumentRecognitionService CreateService(
        IPdfTextExtractor extractor,
        IDocumentRecognitionCache cache,
        IClientResolver? resolver = null) => new(
            extractor,
            new FixedClassifier(),
            new FixedParser(),
            resolver ?? new UnresolvedClientResolver(),
            cache,
            new DocumentRecognitionOptions());

    private static PdfTextExtraction Extraction() => new(
        1,
        [new ExtractedPdfPage(1, "RECIBO DE FERIAS", [])]);

    private static async Task<string> CreatePdfLikeFileAsync()
    {
        var path = Path.Combine(Path.GetTempPath(), $"synthetic-{Guid.NewGuid():N}.pdf");
        await File.WriteAllBytesAsync(path, "%PDF-synthetic-test"u8.ToArray());
        return path;
    }

    private sealed class RecordingExtractor(PdfTextExtraction extraction) : IPdfTextExtractor
    {
        public int CallCount { get; private set; }

        public Task<PdfTextExtraction> ExtractAsync(
            Stream pdfStream,
            DocumentRecognitionOptions options,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Task.FromResult(extraction);
        }
    }

    private sealed class FixedClassifier : IDocumentClassifier
    {
        public DocumentClassification Classify(PdfTextExtraction extraction) => new(
            RecognizedDocumentType.Vacation,
            "test-v1",
            1m,
            ["RECIBO DE FERIAS"]);
    }

    private sealed class FixedParser : IDocumentParser
    {
        public ParsedDocument Parse(
            PdfTextExtraction extraction,
            DocumentClassification classification) => new([], []);
    }

    private sealed class ResolverPrivacyParser : IDocumentParser
    {
        public ParsedDocument Parse(
            PdfTextExtraction extraction,
            DocumentClassification classification)
        {
            var evidence = new EvidenceBox(1, 0, 0, 0, 0, "raw synthetic evidence");
            return new ParsedDocument(
                [
                    new RecognizedField(
                        "EmpregadorCnpj",
                        "11222333000181",
                        "11.***.***/****-81",
                        SemanticFieldRole.EmployerTaxId,
                        .96m,
                        evidence),
                    new RecognizedField(
                        "EmpregadoCpf",
                        "52998224725",
                        "***.982.***-25",
                        SemanticFieldRole.EmployeeCpf,
                        .96m,
                        evidence),
                    new RecognizedField(
                        "SindicatoCnpj",
                        "04252011000110",
                        "04.***.***/****-10",
                        SemanticFieldRole.UnionTaxId,
                        .96m,
                        evidence),
                    new RecognizedField(
                        "ValorTotal",
                        "7654.32",
                        "7654.32",
                        SemanticFieldRole.TotalAmount,
                        .96m,
                        evidence),
                ],
                []);
        }
    }

    private sealed class CapturingClientResolver : IClientResolver
    {
        public ClientResolutionRequest? Request { get; private set; }

        public Task<ClientResolutionResult> ResolveAsync(
            ClientResolutionRequest request,
            CancellationToken cancellationToken)
        {
            Request = request;
            return Task.FromResult(ClientResolutionResult.Unresolved("client.not_resolved"));
        }
    }

    private sealed class UnresolvedClientResolver : IClientResolver
    {
        public Task<ClientResolutionResult> ResolveAsync(
            ClientResolutionRequest request,
            CancellationToken cancellationToken) =>
            Task.FromResult(ClientResolutionResult.Unresolved("client.not_resolved"));
    }

    private sealed class CountingClientResolver : IClientResolver
    {
        public int CallCount { get; private set; }

        public Task<ClientResolutionResult> ResolveAsync(
            ClientResolutionRequest request,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Task.FromResult(ClientResolutionResult.Unresolved("client.not_resolved"));
        }
    }

    private sealed class MemoryCache : IDocumentRecognitionCache
    {
        private DocumentRecognitionResult? value;
        private string? version;

        public Task<DocumentRecognitionResult?> GetAsync(
            string sha256,
            string engineVersion,
            CancellationToken cancellationToken) =>
            Task.FromResult(version == engineVersion && value?.Sha256 == sha256 ? value : null);

        public Task PutAsync(
            DocumentRecognitionResult result,
            string engineVersion,
            CancellationToken cancellationToken)
        {
            value = result;
            version = engineVersion;
            return Task.CompletedTask;
        }
    }
}
