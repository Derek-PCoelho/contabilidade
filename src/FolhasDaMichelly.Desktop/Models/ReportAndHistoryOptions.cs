using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.History;
using FolhasDaMichelly.Application.Preferences;

namespace FolhasDaMichelly.Desktop.Models;

public sealed record ReportScopeOption(DispatchReportScope Value, string Label, string Help)
{
    public override string ToString() => Label;
}

public enum ReportClientFilterKind
{
    AllClients,
    SelectedClient,
}

public sealed record ReportClientFilterOption(
    ReportClientFilterKind Value,
    string Label,
    string Help)
{
    public override string ToString() => Label;
}

public enum DispatchQueueFilterKind
{
    Pending,
    Completed,
    All,
}

public sealed record DispatchQueueFilterOption(
    DispatchQueueFilterKind Value,
    string Label)
{
    public override string ToString() => Label;
}

public sealed record HistoryHourOption(int Hour, string Label)
{
    public override string ToString() => Label;

    public static IReadOnlyList<HistoryHourOption> Create() =>
        Enumerable.Range(0, 24)
            .Select(hour => new HistoryHourOption(hour, $"{hour:00}:00–{hour:00}:59"))
            .ToArray();
}

public sealed record ManualGroupOption(
    Guid GroupId,
    string Label,
    string Detail)
{
    public override string ToString() => Label;
}

public sealed record HistoryCleanupScopeOption(
    HistoryVisibilityRuleKind Value,
    string Label,
    string Help)
{
    public override string ToString() => Label;
}

public sealed record HistoryTimeScopeOption(
    HistoryTimeScope Value,
    string Label,
    string Help)
{
    public override string ToString() => Label;
}

public sealed record HistoryClientFilterOption(Guid? ClientId, string Label)
{
    public override string ToString() => Label;
}

public sealed record HistoryDocumentFilterOption(Guid? DocumentId, string Label)
{
    public override string ToString() => Label;
}

public sealed record CommunicationStatusRow(
    Guid ItemId,
    string ClientName,
    string Period,
    string MessageReference,
    string GroupSummary,
    string DocumentSummary,
    string OperationResult,
    string DeliveryStatus,
    string NextAction,
    bool NeedsAttention);

public sealed record HistoryTimelineRow(
    Guid EventId,
    DateTimeOffset TimestampUtc,
    string Action,
    string ClientName,
    string Context,
    string Result,
    bool NeedsAttention);
