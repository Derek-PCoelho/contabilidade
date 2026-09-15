using System.Globalization;
using System.Text.RegularExpressions;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public sealed partial class DocumentPeriodParser : IDocumentPeriodParser
{
    private static readonly CultureInfo BrazilianCulture = CultureInfo.GetCultureInfo("pt-BR");

    public DocumentPeriod Parse(IReadOnlyList<RecognizedField> fields)
    {
        ArgumentNullException.ThrowIfNull(fields);
        var dueDate = ParseSingleDate(fields.FirstOrDefault(field => field.Role == SemanticFieldRole.DueDate)?.Value);

        var vacation = fields.FirstOrDefault(field => field.Role == SemanticFieldRole.VacationPeriod);
        if (vacation is not null && TryParseRange(vacation.Value, out var vacationStart, out var vacationEnd))
        {
            return new DocumentPeriod(
                DocumentPeriodKind.DateRange,
                null,
                null,
                vacationStart,
                vacationEnd,
                dueDate,
                vacation.Value);
        }

        var eventField = fields.FirstOrDefault(field => field.Role == SemanticFieldRole.EventDate);
        var eventDate = ParseSingleDate(eventField?.Value);
        if (eventDate.HasValue)
        {
            return new DocumentPeriod(
                DocumentPeriodKind.EventDate,
                null,
                null,
                eventDate,
                eventDate,
                dueDate,
                eventField?.Value);
        }

        var competence = fields.FirstOrDefault(field => field.Role == SemanticFieldRole.Competence);
        if (competence is not null && TryParseCompetence(competence.Value, out var month, out var year))
        {
            return new DocumentPeriod(
                month.HasValue ? DocumentPeriodKind.Monthly : DocumentPeriodKind.Annual,
                month,
                year,
                null,
                null,
                dueDate,
                competence.Value);
        }

        var assessment = fields.FirstOrDefault(field => field.Role == SemanticFieldRole.AssessmentPeriod);
        if (assessment is not null)
        {
            if (TryParseRange(assessment.Value, out var assessmentStart, out var assessmentEnd))
            {
                return new DocumentPeriod(
                    DocumentPeriodKind.AssessmentPeriod,
                    null,
                    null,
                    assessmentStart,
                    assessmentEnd,
                    dueDate,
                    assessment.Value);
            }

            var assessmentDate = ParseSingleDate(assessment.Value);
            if (assessmentDate.HasValue)
            {
                return new DocumentPeriod(
                    DocumentPeriodKind.AssessmentPeriod,
                    null,
                    null,
                    assessmentDate,
                    null,
                    dueDate,
                    assessment.Value);
            }
        }

        return DocumentPeriod.Unknown(
            competence?.Value ?? assessment?.Value ?? vacation?.Value ?? eventField?.Value,
            dueDate);
    }

    private static bool TryParseCompetence(string value, out int? month, out int? year)
    {
        var match = CompetencePattern().Match(value.Trim());
        if (match.Success &&
            int.TryParse(match.Groups["month"].Value, CultureInfo.InvariantCulture, out var parsedMonth) &&
            int.TryParse(match.Groups["year"].Value, CultureInfo.InvariantCulture, out var parsedYear) &&
            parsedMonth is >= 1 and <= 12)
        {
            month = parsedMonth;
            year = parsedYear;
            return true;
        }

        if (YearPattern().IsMatch(value.Trim()) &&
            int.TryParse(value.Trim(), CultureInfo.InvariantCulture, out parsedYear))
        {
            month = null;
            year = parsedYear;
            return true;
        }

        month = null;
        year = null;
        return false;
    }

    private static bool TryParseRange(string value, out DateOnly start, out DateOnly end)
    {
        var matches = DatePattern().Matches(value);
        if (matches.Count >= 2 &&
            ParseSingleDate(matches[0].Value) is { } parsedStart &&
            ParseSingleDate(matches[1].Value) is { } parsedEnd &&
            parsedEnd >= parsedStart)
        {
            start = parsedStart;
            end = parsedEnd;
            return true;
        }

        start = default;
        end = default;
        return false;
    }

    private static DateOnly? ParseSingleDate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        return DateOnly.TryParseExact(
                value.Trim(),
                ["yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy"],
                BrazilianCulture,
                DateTimeStyles.None,
                out var date)
            ? date
            : null;
    }

    [GeneratedRegex(@"^(?<month>0?[1-9]|1[0-2])[/\-](?<year>\d{4})$", RegexOptions.CultureInvariant)]
    private static partial Regex CompetencePattern();

    [GeneratedRegex(@"^\d{4}$", RegexOptions.CultureInvariant)]
    private static partial Regex YearPattern();

    [GeneratedRegex(@"(?:\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{4})", RegexOptions.CultureInvariant)]
    private static partial Regex DatePattern();
}
