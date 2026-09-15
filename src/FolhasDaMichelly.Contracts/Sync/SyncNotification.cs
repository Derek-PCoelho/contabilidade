namespace FolhasDaMichelly.Contracts.Sync;

public sealed record SyncNotification(long Checkpoint, string EntityType);
