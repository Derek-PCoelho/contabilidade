using System.Text.Json;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Documents;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase4DocumentGoldenTests
{
    private static readonly JsonSerializerOptions SerializerOptions =
        new(JsonSerializerDefaults.Web);

    private static readonly string FixturesDirectory = Path.Combine(
        AppContext.BaseDirectory,
        "Fixtures",
        "Documents");

    [Fact]
    public async Task SevenSyntheticProfilesMatchGoldenFieldsAndEvidence()
    {
        var extractor = new PdfPigTextExtractor();
        var classifier = new DeterministicDocumentClassifier();
        var parser = new ProfileDocumentParser();
        var expectedFiles = Directory.GetFiles(
            Path.Combine(FixturesDirectory, "Expected"),
            "*.json");

        Assert.Equal(7, expectedFiles.Length);
        foreach (var expectedFile in expectedFiles.Order(StringComparer.Ordinal))
        {
            var expected = JsonSerializer.Deserialize<ExpectedDocument>(
                await File.ReadAllTextAsync(expectedFile),
                SerializerOptions)
                ?? throw new InvalidOperationException($"Invalid golden file: {expectedFile}");
            await using var stream = File.OpenRead(Path.Combine(
                FixturesDirectory,
                "Pdf",
                $"{expected.Case}.pdf"));

            var extraction = await extractor.ExtractAsync(
                stream,
                new DocumentRecognitionOptions(),
                CancellationToken.None);
            var classification = classifier.Classify(extraction);
            var parsed = parser.Parse(extraction, classification);

            Assert.Equal(expected.PageCount, extraction.PageCount);
            Assert.Equal(expected.DocumentType, classification.DocumentType.ToString());
            Assert.Equal(expected.Fields.Count, parsed.Fields.Count);
            foreach (var expectedField in expected.Fields)
            {
                var actual = Assert.Single(parsed.Fields, field => field.Name == expectedField.Name);
                Assert.Equal(expectedField.Value, actual.Value);
                Assert.Equal(expectedField.Role, actual.Role.ToString());
                Assert.Equal(expectedField.Page, actual.Evidence.PageNumber);
                Assert.NotEmpty(actual.Evidence.Snippet);
            }

            Assert.DoesNotContain(parsed.Findings, finding => finding.IsBlocker);
        }
    }

    [Fact]
    public async Task MultiPagePayrollIsOneDocumentAndRepeatedHeadersAreDeduplicated()
    {
        await using var stream = File.OpenRead(Path.Combine(
            FixturesDirectory,
            "Pdf",
            "folha_pagamento.pdf"));
        var extractor = new PdfPigTextExtractor();
        var extraction = await extractor.ExtractAsync(
            stream,
            new DocumentRecognitionOptions(),
            CancellationToken.None);
        var classification = new DeterministicDocumentClassifier().Classify(extraction);
        var parsed = new ProfileDocumentParser().Parse(extraction, classification);

        Assert.Equal(3, extraction.PageCount);
        Assert.Equal(RecognizedDocumentType.Payroll, classification.DocumentType);
        Assert.Single(parsed.Fields, field => field.Name == "EmpregadorCnpj");
        Assert.Equal(3, Assert.Single(parsed.Fields, field => field.Name == "TotalDaFolha").Evidence.PageNumber);
    }

    [Fact]
    public async Task CorruptedOversizedPageCountAndCancellationAreRejected()
    {
        var extractor = new PdfPigTextExtractor();
        await using var corrupted = new MemoryStream("%PDF-not-a-real-document"u8.ToArray());
        var invalid = await Assert.ThrowsAsync<DocumentImportException>(() => extractor.ExtractAsync(
            corrupted,
            new DocumentRecognitionOptions(),
            CancellationToken.None));
        Assert.Equal("document.pdf_invalid_or_protected", invalid.Code);

        await using var payroll = File.OpenRead(Path.Combine(
            FixturesDirectory,
            "Pdf",
            "folha_pagamento.pdf"));
        var pageLimit = await Assert.ThrowsAsync<DocumentImportException>(() => extractor.ExtractAsync(
            payroll,
            new DocumentRecognitionOptions { MaximumPageCount = 2 },
            CancellationToken.None));
        Assert.Equal("document.page_limit_exceeded", pageLimit.Code);

        await using var valid = File.OpenRead(Path.Combine(FixturesDirectory, "Pdf", "ferias.pdf"));
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => extractor.ExtractAsync(
            valid,
            new DocumentRecognitionOptions(),
            cancelled.Token));
    }

    private sealed record ExpectedDocument(
        string Case,
        string DocumentType,
        int PageCount,
        string Confidence,
        IReadOnlyList<ExpectedField> Fields,
        IReadOnlyList<object> Candidates,
        IReadOnlyList<object> Findings);

    private sealed record ExpectedField(
        string Name,
        string Value,
        string Role,
        int Page,
        decimal Confidence);
}
