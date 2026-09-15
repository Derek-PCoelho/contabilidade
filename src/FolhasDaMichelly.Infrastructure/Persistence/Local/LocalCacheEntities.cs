namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class CachedClient
{
    public Guid Id { get; set; }

    public string DisplayName { get; set; } = string.Empty;

    public bool IsActive { get; set; }

    public long Version { get; set; }

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class OfflineSyncOperation
{
    public Guid OperationId { get; set; }

    public Guid ClientId { get; set; }

    public string DisplayName { get; set; } = string.Empty;

    public bool IsActive { get; set; }

    public long ExpectedVersion { get; set; }

    public int AttemptCount { get; set; }

    public DateTimeOffset NextAttemptAtUtc { get; set; }

    public DateTimeOffset CreatedAtUtc { get; set; }

    public bool IsConflicted { get; set; }

    public string? LastErrorCode { get; set; }
}

public sealed class LocalSyncState
{
    public int Id { get; set; }

    public long Checkpoint { get; set; }
}

public sealed class CatalogCacheRecord
{
    public string RecordType { get; set; } = string.Empty;

    public Guid RecordId { get; set; }

    public string JsonPayload { get; set; } = string.Empty;

    public long Version { get; set; }

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class DocumentRecognitionCacheRecord
{
    public string Sha256 { get; set; } = string.Empty;

    public string EngineVersion { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset RecognizedAtUtc { get; set; }
}

public sealed class DocumentReviewRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid DocumentId { get; set; }

    public string Sha256 { get; set; } = string.Empty;

    public string SemanticDuplicateKey { get; set; } = string.Empty;

    public string State { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class DocumentReviewGroupRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid GroupId { get; set; }

    public Guid ClientId { get; set; }

    public string PeriodKey { get; set; } = string.Empty;

    public string State { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class DocumentReviewAuditRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid EventId { get; set; }

    public string Action { get; set; } = string.Empty;

    public Guid? DocumentId { get; set; }

    public Guid? GroupId { get; set; }

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset TimestampUtc { get; set; }
}

public sealed class ProcessingBatchRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid BatchId { get; set; }

    public string State { get; set; } = string.Empty;

    public string OperationMode { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class DispatchItemRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid DispatchItemId { get; set; }

    public Guid BatchId { get; set; }

    public Guid GroupId { get; set; }

    public string State { get; set; } = string.Empty;

    public string DispatchFingerprint { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class DeliveryAttemptRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid AttemptId { get; set; }

    public Guid DispatchItemId { get; set; }

    public string State { get; set; } = string.Empty;

    public string IdempotencyKey { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset StartedAtUtc { get; set; }
}

public sealed class DispatchAuditRecord
{
    public string ScopeKey { get; set; } = string.Empty;

    public Guid EventId { get; set; }

    public string Action { get; set; } = string.Empty;

    public Guid? DispatchItemId { get; set; }

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset TimestampUtc { get; set; }
}
