namespace FolhasDaMichelly.Contracts.Production;

public sealed record ProductionPolicyResponse(
    bool Enabled,
    string EnvironmentName,
    string Stage,
    bool ReadyForSend,
    bool CurrentUserRoleAllowed,
    bool SendEnabled,
    int MaximumBatchSize,
    int MaximumDailySends,
    int AuthorizedSendsToday,
    int RemainingSendsToday,
    string MinimumApplicationVersion,
    IReadOnlyList<string> AllowedRoles,
    IReadOnlyList<string> Blockers);
