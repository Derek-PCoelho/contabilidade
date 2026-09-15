using System.Globalization;
using System.Text;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class DeterministicDocumentClassifier : IDocumentClassifier
{
    private const string ProfileVersion = "phase4-profiles-v1";

    private static readonly Profile[] Profiles =
    [
        new(RecognizedDocumentType.Vacation, ["RECIBO DE FERIAS", "PERIODO DE GOZO"]),
        new(RecognizedDocumentType.FgtsDigital, ["FGTS DIGITAL", "COMPETENCIA"]),
        new(RecognizedDocumentType.Payroll, ["FOLHA DE PAGAMENTO", "TOTAL DA FOLHA"]),
        new(RecognizedDocumentType.FederalRevenueCollection, ["DARF", "PERIODO DE APURACAO"]),
        new(RecognizedDocumentType.ThirteenthSalary, ["DECIMO TERCEIRO SALARIO", "EMPREGADO CPF"]),
        new(RecognizedDocumentType.ProLabore, ["PRO-LABORE", "SOCIO CPF"]),
        new(RecognizedDocumentType.Termination, ["TERMO DE RESCISAO", "TRABALHADOR CPF"]),
    ];

    public DocumentClassification Classify(PdfTextExtraction extraction)
    {
        var normalized = Normalize(extraction.FullText);
        var ranked = Profiles.Select(profile =>
        {
            var matches = profile.Anchors.Where(normalized.Contains).ToArray();
            return new { profile.DocumentType, Matches = matches, Score = (decimal)matches.Length / profile.Anchors.Length };
        }).OrderByDescending(item => item.Score).ThenBy(item => item.DocumentType).First();

        if (ranked.Score < 1m)
        {
            return new DocumentClassification(
                RecognizedDocumentType.Unclassified,
                ProfileVersion,
                ranked.Score * .5m,
                ranked.Matches);
        }

        return new DocumentClassification(
            ranked.DocumentType,
            ProfileVersion,
            ranked.Score,
            ranked.Matches);
    }

    internal static string Normalize(string value)
    {
        var decomposed = value.Normalize(NormalizationForm.FormD);
        var builder = new StringBuilder(decomposed.Length);
        foreach (var character in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark)
            {
                builder.Append(char.ToUpperInvariant(character));
            }
        }

        return string.Join(' ', builder.ToString().Normalize(NormalizationForm.FormC)
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
    }

    private sealed record Profile(
        RecognizedDocumentType DocumentType,
        string[] Anchors);
}
