namespace FolhasDaMichelly.Application.Preferences;

public sealed record WorkspacePreferences(
    string? InputFolderPath,
    string? DocumentArchiveDirectory,
    string? ReportOutputDirectory,
    bool IncludeSubfolders,
    int? SelectedYear,
    int? SelectedMonth,
    bool KeepEmailSession,
    int RetentionReviewMonths = 0,
    string UpdateChannel = "stable",
    HistoryVisibilityState? HistoryVisibility = null)
{
    public static WorkspacePreferences Default(DateTimeOffset now) => new(
        null,
        null,
        null,
        true,
        now.Year,
        now.Month,
        true,
        0,
        "stable",
        HistoryVisibilityState.Empty);
}

public enum HistoryVisibilityRuleKind
{
    AllUntilNow,
    SelectedOperationalPeriod,
    CalendarDay,
    ClockHour,
    CustomInterval,
}

public sealed record HistoryVisibilityRule(
    Guid Id,
    HistoryVisibilityRuleKind Kind,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset? StartUtc,
    DateTimeOffset? EndExclusiveUtc,
    int? Year,
    int? Month,
    IReadOnlyList<Guid> EventIds);

public sealed record HistoryVisibilityState(IReadOnlyList<HistoryVisibilityRule> Rules)
{
    public static HistoryVisibilityState Empty { get; } = new([]);
}

public interface IWorkspacePreferencesStore
{
    Task<WorkspacePreferences?> LoadAsync(CancellationToken cancellationToken);

    Task SaveAsync(WorkspacePreferences preferences, CancellationToken cancellationToken);
}
