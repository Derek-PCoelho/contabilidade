namespace FolhasDaMichelly.Contracts.Sync;

public sealed record ClientSyncItem(
    Guid Id,
    string DisplayName,
    bool IsActive,
    long Version,
    DateTimeOffset UpdatedAtUtc);

public sealed record UpsertClientCommand(
    Guid OperationId,
    Guid ClientId,
    string DisplayName,
    bool IsActive,
    long ExpectedVersion);

public sealed record PushSyncRequest(IReadOnlyList<UpsertClientCommand> Commands);

public sealed record PushSyncResponse(IReadOnlyList<SyncCommandResult> Results);

public sealed record PullSyncResponse(long Checkpoint, IReadOnlyList<ClientSyncItem> Clients);

public sealed record SyncCommandResult(
    Guid OperationId,
    SyncCommandStatus Status,
    ClientSyncItem? Current,
    string? ErrorCode);

public enum SyncCommandStatus
{
    Applied,
    Duplicate,
    Conflict,
    Rejected,
}

public static class SyncErrorCodes
{
    public const string Conflict = "SYNC_CONFLICT";
    public const string InvalidCommand = "SYNC_INVALID_COMMAND";
}
