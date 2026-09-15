using System.Globalization;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class ProfileDocumentParser : IDocumentParser
{
    public ParsedDocument Parse(
        PdfTextExtraction extraction,
        DocumentClassification classification)
    {
        if (classification.DocumentType == RecognizedDocumentType.Unclassified)
        {
            return new ParsedDocument(
                [],
                [new RecognitionFinding(
                    "document.profile_not_recognized",
                    "Nenhum dos sete perfis documentais foi reconhecido com segurança.",
                    true)]);
        }

        var fields = classification.DocumentType switch
        {
            RecognizedDocumentType.Vacation => ParseVacation(extraction),
            RecognizedDocumentType.FgtsDigital => ParseFgts(extraction),
            RecognizedDocumentType.Payroll => ParsePayroll(extraction),
            RecognizedDocumentType.FederalRevenueCollection => ParseFederalCollection(extraction),
            RecognizedDocumentType.ThirteenthSalary => ParseThirteenthSalary(extraction),
            RecognizedDocumentType.ProLabore => ParseProLabore(extraction),
            RecognizedDocumentType.Termination => ParseTermination(extraction),
            _ => [],
        };

        var findings = new List<RecognitionFinding>();
        if (!fields.Any(field => field.Role is SemanticFieldRole.EmployerTaxId or SemanticFieldRole.ClientTaxId))
        {
            findings.Add(new RecognitionFinding(
                "document.client_identifier_missing",
                "O perfil foi reconhecido, mas não foi localizado identificador elegível do cliente.",
                true));
        }

        return new ParsedDocument(fields, findings);
    }

    private static List<RecognizedField> ParseVacation(PdfTextExtraction extraction) =>
        Read(extraction,
            ("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("Empregador", "EMPREGADOR", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("EmpregadoCpf", "EMPREGADO CPF", SemanticFieldRole.EmployeeCpf, FieldKind.TaxId),
            ("PeriodoDeGozo", "PERIODO DE GOZO", SemanticFieldRole.VacationPeriod, FieldKind.Text),
            ("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> ParseFgts(PdfTextExtraction extraction) =>
        Read(extraction,
            ("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("RazaoSocial", "RAZAO SOCIAL", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("Competencia", "COMPETENCIA", SemanticFieldRole.Competence, FieldKind.Text),
            ("Vencimento", "VENCIMENTO", SemanticFieldRole.DueDate, FieldKind.Date),
            ("ValorTotal", "VALOR TOTAL", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> ParsePayroll(PdfTextExtraction extraction) =>
        Read(extraction,
            ("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("Empregador", "EMPREGADOR", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("Competencia", "COMPETENCIA", SemanticFieldRole.Competence, FieldKind.Text),
            ("TotalDaFolha", "TOTAL DA FOLHA", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> ParseFederalCollection(PdfTextExtraction extraction) =>
        Read(extraction,
            ("ContribuinteCnpj", "CONTRIBUINTE CNPJ", SemanticFieldRole.ClientTaxId, FieldKind.TaxId),
            ("RazaoSocial", "RAZAO SOCIAL", SemanticFieldRole.ClientName, FieldKind.Text),
            ("PeriodoApuracao", "PERIODO DE APURACAO", SemanticFieldRole.AssessmentPeriod, FieldKind.Date),
            ("Vencimento", "VENCIMENTO", SemanticFieldRole.DueDate, FieldKind.Date),
            ("ValorTotal", "VALOR TOTAL", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> ParseThirteenthSalary(PdfTextExtraction extraction)
    {
        var fields = Read(extraction,
            ("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("Empregador", "EMPREGADOR", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("EmpregadoCpf", "EMPREGADO CPF", SemanticFieldRole.EmployeeCpf, FieldKind.TaxId),
            ("Competencia", "COMPETENCIA", SemanticFieldRole.Competence, FieldKind.Text),
            ("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TotalAmount, FieldKind.Amount));
        AppendDistinctRepeatedTaxIds(
            extraction,
            "EMPREGADOR CNPJ",
            "EmpregadorCnpj",
            SemanticFieldRole.EmployerTaxId,
            fields);
        return fields;
    }

    private static List<RecognizedField> ParseProLabore(PdfTextExtraction extraction) =>
        Read(extraction,
            ("EmpresaCnpj", "EMPRESA CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("Empresa", "EMPRESA", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("SocioCpf", "SOCIO CPF", SemanticFieldRole.PartnerCpf, FieldKind.TaxId),
            ("Competencia", "COMPETENCIA", SemanticFieldRole.Competence, FieldKind.Text),
            ("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> ParseTermination(PdfTextExtraction extraction) =>
        Read(extraction,
            ("SindicatoCnpj", "SINDICATO CNPJ", SemanticFieldRole.UnionTaxId, FieldKind.TaxId),
            ("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EmployerTaxId, FieldKind.TaxId),
            ("Empregador", "EMPREGADOR", SemanticFieldRole.EmployerName, FieldKind.Text),
            ("TrabalhadorCpf", "TRABALHADOR CPF", SemanticFieldRole.EmployeeCpf, FieldKind.TaxId),
            ("DataDesligamento", "DATA DE DESLIGAMENTO", SemanticFieldRole.EventDate, FieldKind.Date),
            ("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TotalAmount, FieldKind.Amount));

    private static List<RecognizedField> Read(
        PdfTextExtraction extraction,
        params (string Name, string Label, SemanticFieldRole Role, FieldKind Kind)[] specifications)
    {
        var result = new List<RecognizedField>();
        foreach (var specification in specifications)
        {
            var found = FindValue(extraction, specification.Label);
            if (found is null)
            {
                continue;
            }

            var normalizedValue = NormalizeValue(found.Value.Value, specification.Kind);
            if (normalizedValue.Length == 0)
            {
                continue;
            }

            result.Add(new RecognizedField(
                specification.Name,
                normalizedValue,
                DisplayValue(normalizedValue, specification.Kind),
                specification.Role,
                .96m,
                found.Value.Evidence));
        }

        return result;
    }

    private static (string Value, EvidenceBox Evidence)? FindValue(
        PdfTextExtraction extraction,
        string label)
    {
        var normalizedLabel = DeterministicDocumentClassifier.Normalize(label);
        foreach (var page in extraction.Pages)
        {
            var lines = page.Text.Replace("\r\n", "\n", StringComparison.Ordinal)
                .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            foreach (var line in lines)
            {
                var separator = line.IndexOf(':');
                if (separator < 0)
                {
                    continue;
                }

                var lineLabel = DeterministicDocumentClassifier.Normalize(line[..separator]);
                if (!string.Equals(lineLabel, normalizedLabel, StringComparison.Ordinal))
                {
                    continue;
                }

                var value = line[(separator + 1)..].Trim();
                var token = value.Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? value;
                var word = page.Words.FirstOrDefault(candidate =>
                    candidate.Text.Contains(token, StringComparison.OrdinalIgnoreCase));
                var evidence = word is null
                    ? new EvidenceBox(page.PageNumber, 0, 0, 0, 0, line)
                    : new EvidenceBox(
                        word.PageNumber,
                        word.X,
                        word.Y,
                        word.Width,
                        word.Height,
                        line);
                return (value, evidence);
            }
        }

        return null;
    }

    private static void AppendDistinctRepeatedTaxIds(
        PdfTextExtraction extraction,
        string label,
        string fieldName,
        SemanticFieldRole role,
        List<RecognizedField> fields)
    {
        var normalizedLabel = DeterministicDocumentClassifier.Normalize(label);
        var existing = fields.Where(field => field.Role == role)
            .Select(field => field.Value)
            .ToHashSet(StringComparer.Ordinal);
        foreach (var page in extraction.Pages)
        {
            var lines = page.Text.Replace("\r\n", "\n", StringComparison.Ordinal)
                .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            foreach (var line in lines)
            {
                var separator = line.IndexOf(':');
                if (separator < 0 ||
                    !string.Equals(
                        DeterministicDocumentClassifier.Normalize(line[..separator]),
                        normalizedLabel,
                        StringComparison.Ordinal))
                {
                    continue;
                }

                var value = NormalizeValue(line[(separator + 1)..], FieldKind.TaxId);
                if (value.Length != 14 || !existing.Add(value))
                {
                    continue;
                }

                var token = line[(separator + 1)..].Trim()
                    .Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? value;
                var word = page.Words.FirstOrDefault(candidate =>
                    candidate.Text.Contains(token, StringComparison.OrdinalIgnoreCase));
                var evidence = word is null
                    ? new EvidenceBox(page.PageNumber, 0, 0, 0, 0, line)
                    : new EvidenceBox(
                        word.PageNumber,
                        word.X,
                        word.Y,
                        word.Width,
                        word.Height,
                        line);
                fields.Add(new RecognizedField(
                    $"{fieldName}{existing.Count}",
                    value,
                    DisplayValue(value, FieldKind.TaxId),
                    role,
                    .96m,
                    evidence));
            }
        }
    }

    private static string NormalizeValue(string value, FieldKind kind) => kind switch
    {
        FieldKind.TaxId => new string(value.Where(char.IsAsciiDigit).ToArray()),
        FieldKind.Amount => NormalizeAmount(value),
        FieldKind.Date => NormalizeDate(value),
        _ => string.Join(' ', value.Split((char[]?)null,
            StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)),
    };

    private static string NormalizeAmount(string value)
    {
        var clean = value.Replace("R$", string.Empty, StringComparison.OrdinalIgnoreCase).Trim();
        return decimal.TryParse(clean, NumberStyles.Currency, CultureInfo.GetCultureInfo("pt-BR"), out var amount)
            ? amount.ToString("0.00", CultureInfo.InvariantCulture)
            : clean;
    }

    private static string NormalizeDate(string value) =>
        DateOnly.TryParse(value, CultureInfo.GetCultureInfo("pt-BR"), out var date)
            ? date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
            : value.Trim();

    private static string DisplayValue(string value, FieldKind kind)
    {
        if (kind != FieldKind.TaxId)
        {
            return value;
        }

        return value.Length switch
        {
            14 => $"{value[..2]}.***.***/****-{value[^2..]}",
            11 => $"***.{value.Substring(3, 3)}.***-{value[^2..]}",
            _ => "***",
        };
    }

    private enum FieldKind
    {
        Text,
        TaxId,
        Amount,
        Date,
    }
}
