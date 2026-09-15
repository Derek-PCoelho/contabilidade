using System.Globalization;
using System.Text;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.History;

public enum HistoryTimeScope
{
    All,
    CalendarDay,
    CalendarMonth,
    CalendarYear,
    CustomInterval,
}

public sealed record HistoryTimeWindow(
    HistoryTimeScope Scope,
    DateTimeOffset? StartUtc,
    DateTimeOffset? EndExclusiveUtc)
{
    public static HistoryTimeWindow All { get; } = new(HistoryTimeScope.All, null, null);

    public static HistoryTimeWindow ForDay(DateOnly day, TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(timeZone);
        var start = ToUtc(day.ToDateTime(TimeOnly.MinValue), timeZone);
        var end = ToUtc(day.AddDays(1).ToDateTime(TimeOnly.MinValue), timeZone);
        return new HistoryTimeWindow(HistoryTimeScope.CalendarDay, start, end);
    }

    public static HistoryTimeWindow ForMonth(int year, int month, TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(timeZone);
        var firstDay = new DateOnly(year, month, 1);
        var start = ToUtc(firstDay.ToDateTime(TimeOnly.MinValue), timeZone);
        var end = ToUtc(firstDay.AddMonths(1).ToDateTime(TimeOnly.MinValue), timeZone);
        return new HistoryTimeWindow(HistoryTimeScope.CalendarMonth, start, end);
    }

    public static HistoryTimeWindow ForYear(int year, TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(timeZone);
        var firstDay = new DateOnly(year, 1, 1);
        var start = ToUtc(firstDay.ToDateTime(TimeOnly.MinValue), timeZone);
        var end = ToUtc(firstDay.AddYears(1).ToDateTime(TimeOnly.MinValue), timeZone);
        return new HistoryTimeWindow(HistoryTimeScope.CalendarYear, start, end);
    }

    public static HistoryTimeWindow ForLocalInterval(
        DateOnly startDate,
        TimeOnly startTime,
        DateOnly endDate,
        TimeOnly endTime,
        TimeZoneInfo timeZone)
    {
        ArgumentNullException.ThrowIfNull(timeZone);
        var start = ToUtc(startDate.ToDateTime(startTime), timeZone);
        var end = ToUtc(endDate.ToDateTime(endTime), timeZone);
        return ForUtcInterval(start, end);
    }

    public static HistoryTimeWindow ForUtcInterval(
        DateTimeOffset startUtc,
        DateTimeOffset endExclusiveUtc)
    {
        var normalizedStart = startUtc.ToUniversalTime();
        var normalizedEnd = endExclusiveUtc.ToUniversalTime();
        if (normalizedEnd <= normalizedStart)
        {
            throw new ArgumentOutOfRangeException(
                nameof(endExclusiveUtc),
                "O fim do intervalo deve ser posterior ao início.");
        }

        return new HistoryTimeWindow(
            HistoryTimeScope.CustomInterval,
            normalizedStart,
            normalizedEnd);
    }

    public bool Contains(DateTimeOffset timestampUtc)
    {
        if (Scope == HistoryTimeScope.All)
        {
            return true;
        }

        return StartUtc is { } start && timestampUtc >= start &&
            EndExclusiveUtc is { } end && timestampUtc < end;
    }

    private static DateTimeOffset ToUtc(DateTime localDateTime, TimeZoneInfo timeZone)
    {
        var unspecified = DateTime.SpecifyKind(localDateTime, DateTimeKind.Unspecified);
        if (timeZone.IsInvalidTime(unspecified))
        {
            throw new ArgumentOutOfRangeException(
                nameof(localDateTime),
                "O horário escolhido não existe no fuso local por causa da mudança de horário.");
        }

        var offsets = timeZone.IsAmbiguousTime(unspecified)
            ? timeZone.GetAmbiguousTimeOffsets(unspecified)
            : [timeZone.GetUtcOffset(unspecified)];
        var offset = offsets.Max();
        return new DateTimeOffset(unspecified, offset).ToUniversalTime();
    }
}

public sealed record HistoryCompetence(int Year, int? Month)
{
    public bool Matches(int? year, int? month) =>
        (!year.HasValue || Year == year.Value) &&
        (!month.HasValue || Month == month.Value);

    public static bool TryParse(string? value, out HistoryCompetence competence)
    {
        competence = default!;
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        var numbers = value
            .Split(['/', '-', ':', '_', ' '], StringSplitOptions.RemoveEmptyEntries)
            .Select(part => int.TryParse(part, CultureInfo.InvariantCulture, out var number) ? number : -1)
            .Where(number => number >= 0)
            .ToArray();
        var yearIndex = Array.FindIndex(numbers, number => number is >= 1900 and <= 9999);
        if (yearIndex < 0)
        {
            return false;
        }

        int? month = null;
        if (yearIndex > 0 && numbers[yearIndex - 1] is >= 1 and <= 12)
        {
            month = numbers[yearIndex - 1];
        }
        else if (yearIndex + 1 < numbers.Length && numbers[yearIndex + 1] is >= 1 and <= 12)
        {
            month = numbers[yearIndex + 1];
        }

        competence = new HistoryCompetence(numbers[yearIndex], month);
        return true;
    }
}

public sealed record HistoryDocumentReference(
    Guid? DocumentId,
    RecognizedDocumentType? DocumentType,
    string DisplayName);

public sealed record HistoryFilterEntry(
    Guid EventId,
    DateTimeOffset TimestampUtc,
    Guid? ClientId,
    string ClientName,
    IReadOnlyList<HistoryCompetence> Competences,
    IReadOnlyList<HistoryDocumentReference> Documents,
    string SearchText);

public sealed record HistoryFilterCriteria(
    HistoryTimeWindow TimeWindow,
    int? CompetenceYear = null,
    int? CompetenceMonth = null,
    Guid? ClientId = null,
    Guid? DocumentId = null,
    RecognizedDocumentType? DocumentType = null,
    string? SearchText = null)
{
    public static HistoryFilterCriteria All { get; } = new(HistoryTimeWindow.All);
}

public static class HistoryFilterEngine
{
    public static IEnumerable<T> Apply<T>(
        IEnumerable<T> source,
        Func<T, HistoryFilterEntry> projection,
        HistoryFilterCriteria filter)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(projection);
        ArgumentNullException.ThrowIfNull(filter);

        return source.Where(item => Matches(projection(item), filter));
    }

    public static bool Matches(HistoryFilterEntry entry, HistoryFilterCriteria filter)
    {
        ArgumentNullException.ThrowIfNull(entry);
        ArgumentNullException.ThrowIfNull(filter);

        if (!filter.TimeWindow.Contains(entry.TimestampUtc) ||
            filter.ClientId is { } clientId && entry.ClientId != clientId)
        {
            return false;
        }

        if ((filter.CompetenceYear.HasValue || filter.CompetenceMonth.HasValue) &&
            !entry.Competences.Any(item => item.Matches(
                filter.CompetenceYear,
                filter.CompetenceMonth)))
        {
            return false;
        }

        if (filter.DocumentId is { } documentId &&
            !entry.Documents.Any(item => item.DocumentId == documentId))
        {
            return false;
        }

        if (filter.DocumentType is { } documentType &&
            !entry.Documents.Any(item => item.DocumentType == documentType))
        {
            return false;
        }

        var query = NormalizeSearch(filter.SearchText);
        if (query.Length == 0)
        {
            return true;
        }

        var searchable = NormalizeSearch(string.Join(
            ' ',
            entry.ClientName,
            entry.SearchText,
            string.Join(' ', entry.Documents.Select(item => item.DisplayName))));
        return query
            .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .All(term => searchable.Contains(term, StringComparison.Ordinal));
    }

    private static string NormalizeSearch(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return string.Empty;
        }

        var builder = new StringBuilder(value.Length);
        foreach (var character in value.Trim().Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(character) != UnicodeCategory.NonSpacingMark)
            {
                builder.Append(char.ToUpperInvariant(character));
            }
        }

        return builder.ToString().Normalize(NormalizationForm.FormC);
    }
}

public sealed class HistoryProjectionIndex
{
    private readonly IReadOnlyDictionary<Guid, ReviewDocument> documents;
    private readonly IReadOnlyDictionary<Guid, DocumentDispatchGroup> groups;
    private readonly IReadOnlyDictionary<Guid, DispatchItem> dispatchItems;
    private readonly IReadOnlyDictionary<Guid, string> clientNames;

    private HistoryProjectionIndex(
        IReadOnlyDictionary<Guid, ReviewDocument> documents,
        IReadOnlyDictionary<Guid, DocumentDispatchGroup> groups,
        IReadOnlyDictionary<Guid, DispatchItem> dispatchItems,
        IReadOnlyDictionary<Guid, string> clientNames)
    {
        this.documents = documents;
        this.groups = groups;
        this.dispatchItems = dispatchItems;
        this.clientNames = clientNames;
    }

    public static HistoryProjectionIndex Create(
        IEnumerable<ReviewDocument> documents,
        IEnumerable<DocumentDispatchGroup> groups,
        IEnumerable<DispatchItem> dispatchItems,
        IEnumerable<KeyValuePair<Guid, string>>? clientNames = null)
    {
        ArgumentNullException.ThrowIfNull(documents);
        ArgumentNullException.ThrowIfNull(groups);
        ArgumentNullException.ThrowIfNull(dispatchItems);

        return new HistoryProjectionIndex(
            documents.GroupBy(item => item.Id).ToDictionary(item => item.Key, item => item.Last()),
            groups.GroupBy(item => item.Id).ToDictionary(item => item.Key, item => item.Last()),
            dispatchItems.GroupBy(item => item.Id).ToDictionary(item => item.Key, item => item.Last()),
            (clientNames ?? [])
                .GroupBy(item => item.Key)
                .ToDictionary(item => item.Key, item => item.Last().Value));
    }

    public HistoryFilterEntry Project(ReviewAuditEvent audit)
    {
        ArgumentNullException.ThrowIfNull(audit);
        var auditDocument = audit.DocumentId is { } documentId && documents.TryGetValue(documentId, out var foundDocument)
            ? foundDocument
            : null;
        var group = ResolveGroup(audit.GroupId ?? auditDocument?.GroupId);
        var relatedDocuments = audit.DocumentId.HasValue
            ? auditDocument is null ? [] : [auditDocument]
            : ResolveGroupDocuments(group);
        var metadataClientId = TryGetGuid(GetAuditValue(audit, "client"));
        var clientId = metadataClientId ?? auditDocument?.ClientId ?? group?.ClientId;
        var clientName = GetAuditValue(audit, "client-name") ??
            auditDocument?.ClientDisplayName ??
            group?.ClientDisplayName ??
            GetClientName(clientId);
        var competences = relatedDocuments.SelectMany(item => GetCompetences(item.Period)).ToList();
        if (!audit.DocumentId.HasValue && competences.Count == 0 && group is not null)
        {
            AddCompetence(competences, group.PeriodKey);
            AddCompetence(competences, group.PeriodLabel);
        }

        AddAuditCompetences(competences, audit.PreviousValue);
        AddAuditCompetences(competences, audit.NewValue);

        var references = relatedDocuments.Select(ToReference).ToList();
        var hasDocumentType = Enum.TryParse<RecognizedDocumentType>(
            GetAuditValue(audit, "type"),
            ignoreCase: true,
            out var documentType);
        if (references.Count == 0 && (audit.DocumentId.HasValue || hasDocumentType))
        {
            references.Add(new HistoryDocumentReference(
                audit.DocumentId,
                hasDocumentType ? documentType : null,
                hasDocumentType
                    ? DocumentPresentation.ToPortugueseLabel(documentType)
                    : "Documento histórico"));
        }

        return new HistoryFilterEntry(
            audit.Id,
            audit.TimestampUtc,
            clientId,
            clientName,
            Distinct(competences),
            Distinct(references),
            string.Join(' ', audit.Action, audit.Reason, audit.PreviousValue, audit.NewValue));
    }

    public HistoryFilterEntry Project(DispatchAuditEvent audit)
    {
        ArgumentNullException.ThrowIfNull(audit);
        var item = audit.DispatchItemId is { } itemId && dispatchItems.TryGetValue(itemId, out var foundItem)
            ? foundItem
            : null;
        var group = ResolveGroup(audit.GroupId ?? item?.GroupId);
        var clientId = item?.ClientId ?? group?.ClientId;
        var clientName = item?.ClientDisplayName ?? group?.ClientDisplayName ?? GetClientName(clientId);
        var relatedDocuments = ResolveGroupDocuments(group);
        var references = item?.Message?.Attachments.Select(attachment => new HistoryDocumentReference(
                attachment.DocumentId,
                attachment.DocumentType,
                attachment.FileName))
            .ToList() ?? relatedDocuments.Select(ToReference).ToList();
        var competences = new List<HistoryCompetence>();
        AddCompetence(competences, item?.PeriodLabel);
        AddCompetence(competences, group?.PeriodKey);
        AddCompetence(competences, group?.PeriodLabel);
        competences.AddRange(relatedDocuments.SelectMany(document => GetCompetences(document.Period)));

        return new HistoryFilterEntry(
            audit.Id,
            audit.TimestampUtc,
            clientId,
            clientName,
            Distinct(competences),
            Distinct(references),
            string.Join(' ', audit.Action, audit.Outcome, audit.ErrorCode, audit.CorrelationId));
    }

    public HistoryFilterEntry Project(
        AuditEventModel audit,
        Guid? clientId = null,
        string? clientName = null)
    {
        ArgumentNullException.ThrowIfNull(audit);
        var parsedClientId = clientId ?? TryGetGuid(audit.EntityId);
        return new HistoryFilterEntry(
            audit.Id,
            audit.TimestampUtc,
            parsedClientId,
            clientName ?? GetClientName(parsedClientId),
            [],
            [],
            string.Join(' ', audit.Action, audit.Category, audit.Severity, audit.RedactedDataJson));
    }

    private DocumentDispatchGroup? ResolveGroup(Guid? groupId) =>
        groupId is { } id && groups.TryGetValue(id, out var group) ? group : null;

    private ReviewDocument[] ResolveGroupDocuments(DocumentDispatchGroup? group)
    {
        if (group is not null)
        {
            return group.DocumentIds
                .Where(documents.ContainsKey)
                .Select(id => documents[id])
                .ToArray();
        }

        return [];
    }

    private string GetClientName(Guid? clientId) =>
        clientId is { } id && clientNames.TryGetValue(id, out var name) && !string.IsNullOrWhiteSpace(name)
            ? name
            : "Cliente não disponível";

    private static HistoryDocumentReference ToReference(ReviewDocument document) => new(
        document.Id,
        document.DocumentType,
        $"{document.FileName} {DocumentPresentation.ToPortugueseLabel(document.DocumentType)}");

    private static IEnumerable<HistoryCompetence> GetCompetences(DocumentPeriod period)
    {
        var year = period.Year ?? period.StartDate?.Year;
        var month = period.Month ?? period.StartDate?.Month;
        if (year is { } effectiveYear)
        {
            yield return new HistoryCompetence(effectiveYear, month);
            yield break;
        }

        if (HistoryCompetence.TryParse(period.OriginalText, out var parsed))
        {
            yield return parsed;
        }
    }

    private static void AddCompetence(List<HistoryCompetence> competences, string? value)
    {
        if (HistoryCompetence.TryParse(value, out var competence))
        {
            competences.Add(competence);
        }
    }

    private static void AddAuditCompetences(
        List<HistoryCompetence> competences,
        string? auditValue)
    {
        if (string.IsNullOrWhiteSpace(auditValue))
        {
            return;
        }

        foreach (var key in new[] { "period", "periods" })
        {
            foreach (var value in GetAuditValues(auditValue, key)
                .SelectMany(item => item.Split(
                    '|',
                    StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)))
            {
                AddCompetence(competences, value);
            }
        }

        AddCompetence(competences, auditValue);
    }

    private static T[] Distinct<T>(IEnumerable<T> values) where T : notnull =>
        values.Distinct().ToArray();

    private static Guid? TryGetGuid(string? value) =>
        Guid.TryParse(value, out var parsed) ? parsed : null;

    private static string? GetAuditValue(ReviewAuditEvent audit, string key) =>
        GetAuditValue(audit.NewValue, key) ?? GetAuditValue(audit.PreviousValue, key);

    private static string? GetAuditValue(string? value, string key) =>
        GetAuditValues(value, key).FirstOrDefault();

    private static IEnumerable<string> GetAuditValues(string? value, string key) =>
        (value ?? string.Empty)
        .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Select(part => part.Split(':', 2, StringSplitOptions.TrimEntries))
        .Where(parts => parts.Length == 2 && string.Equals(parts[0], key, StringComparison.OrdinalIgnoreCase))
        .Select(parts => parts[1]);
}
