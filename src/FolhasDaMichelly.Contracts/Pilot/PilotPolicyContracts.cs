namespace FolhasDaMichelly.Contracts.Pilot;

public sealed record PilotPolicyResponse(
    bool Enabled,
    string EnvironmentName,
    bool AllowTest,
    bool AllowDraft,
    bool AllowSend,
    bool RequireNonProductionData,
    int MaximumClients,
    string MinimumApplicationVersion);
