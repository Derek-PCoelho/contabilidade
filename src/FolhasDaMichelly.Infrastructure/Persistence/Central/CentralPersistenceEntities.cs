namespace FolhasDaMichelly.Infrastructure.Persistence.Central;

public sealed class SyncOperation
{
    public Guid OrganizationId { get; set; }

    public Guid OperationId { get; set; }

    public Guid EntityId { get; set; }

    public string Status { get; set; } = string.Empty;

    public long ResultVersion { get; set; }

    public DateTimeOffset AppliedAtUtc { get; set; }
}

public sealed class SyncChange
{
    public long Checkpoint { get; set; }

    public Guid OrganizationId { get; set; }

    public Guid EntityId { get; set; }

    public string EntityType { get; set; } = string.Empty;

    public DateTimeOffset ChangedAtUtc { get; set; }
}

public sealed class AuditEvent
{
    public Guid Id { get; set; }

    public Guid OrganizationId { get; set; }

    public Guid UserId { get; set; }

    public Guid? DeviceId { get; set; }

    public string EntityType { get; set; } = string.Empty;

    public string EntityId { get; set; } = string.Empty;

    public string Action { get; set; } = string.Empty;

    public string Category { get; set; } = string.Empty;

    public string Severity { get; set; } = string.Empty;

    public string RedactedDataJson { get; set; } = "{}";

    public DateTimeOffset TimestampUtc { get; set; }

    public Guid CorrelationId { get; set; }
}

public sealed class ProductionDispatchAuthorization
{
    public Guid OperationId { get; set; }

    public Guid OrganizationId { get; set; }

    public Guid UserId { get; set; }

    public Guid DeviceId { get; set; }

    public string ProviderKey { get; set; } = string.Empty;

    public string DispatchFingerprint { get; set; } = string.Empty;

    public int BatchSize { get; set; }

    public int AttachmentCount { get; set; }

    public string ApplicationVersion { get; set; } = string.Empty;

    public string AuthorizationDateUtc { get; set; } = string.Empty;

    public DateTimeOffset AuthorizedAtUtc { get; set; }
}
