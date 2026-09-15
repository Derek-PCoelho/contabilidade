namespace FolhasDaMichelly.Contracts.Updates;

public sealed record AppReleasePolicyResponse(
    string MinimumSupportedVersion,
    bool IsSupported,
    bool EmailSendEnabled,
    bool BetaChannelEnabled,
    bool StableChannelEnabled);
