using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class LocalCacheDbContext(DbContextOptions<LocalCacheDbContext> options)
    : DbContext(options)
{
    public DbSet<CachedClient> Clients => Set<CachedClient>();

    public DbSet<OfflineSyncOperation> OfflineOperations => Set<OfflineSyncOperation>();

    public DbSet<LocalSyncState> SyncState => Set<LocalSyncState>();

    public DbSet<CatalogCacheRecord> CatalogRecords => Set<CatalogCacheRecord>();

    public DbSet<DocumentRecognitionCacheRecord> DocumentRecognitions =>
        Set<DocumentRecognitionCacheRecord>();

    public DbSet<DocumentReviewRecord> DocumentReviews => Set<DocumentReviewRecord>();

    public DbSet<DocumentReviewGroupRecord> DocumentReviewGroups =>
        Set<DocumentReviewGroupRecord>();

    public DbSet<DocumentReviewAuditRecord> DocumentReviewAudit =>
        Set<DocumentReviewAuditRecord>();

    public DbSet<ProcessingBatchRecord> ProcessingBatches => Set<ProcessingBatchRecord>();

    public DbSet<DispatchItemRecord> DispatchItems => Set<DispatchItemRecord>();

    public DbSet<DeliveryAttemptRecord> DeliveryAttempts => Set<DeliveryAttemptRecord>();

    public DbSet<DispatchAuditRecord> DispatchAudit => Set<DispatchAuditRecord>();

    public DbSet<WorkspacePreferenceRecord> WorkspacePreferences =>
        Set<WorkspacePreferenceRecord>();

    public DbSet<IncidentRecordEntity> Incidents => Set<IncidentRecordEntity>();

    public DbSet<IncidentAuditRecordEntity> IncidentAudit => Set<IncidentAuditRecordEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        ArgumentNullException.ThrowIfNull(modelBuilder);

        modelBuilder.Entity<CachedClient>(entity =>
        {
            entity.ToTable("cached_clients");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.DisplayName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
        });

        modelBuilder.Entity<OfflineSyncOperation>(entity =>
        {
            entity.ToTable("offline_sync_operations");
            entity.HasKey(item => item.OperationId);
            entity.Property(item => item.DisplayName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.LastErrorCode).HasMaxLength(80);
            entity.Property(item => item.NextAttemptAtUtc).HasConversion<long>();
            entity.Property(item => item.CreatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.IsConflicted, item.NextAttemptAtUtc });
        });

        modelBuilder.Entity<LocalSyncState>(entity =>
        {
            entity.ToTable("sync_state");
            entity.HasKey(item => item.Id);
        });

        modelBuilder.Entity<CatalogCacheRecord>(entity =>
        {
            entity.ToTable("catalog_cache");
            entity.HasKey(item => new { item.RecordType, item.RecordId });
            entity.Property(item => item.RecordType).HasMaxLength(40);
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.RecordType, item.UpdatedAtUtc });
        });

        modelBuilder.Entity<DocumentRecognitionCacheRecord>(entity =>
        {
            entity.ToTable("document_recognition_cache");
            entity.HasKey(item => item.Sha256);
            entity.Property(item => item.Sha256).HasMaxLength(64);
            entity.Property(item => item.EngineVersion).HasMaxLength(40).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.RecognizedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.EngineVersion, item.RecognizedAtUtc });
        });

        modelBuilder.Entity<DocumentReviewRecord>(entity =>
        {
            entity.ToTable("document_reviews");
            entity.HasKey(item => new { item.ScopeKey, item.DocumentId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.Sha256).HasMaxLength(64).IsRequired();
            entity.Property(item => item.SemanticDuplicateKey).HasMaxLength(500);
            entity.Property(item => item.State).HasMaxLength(40).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.Sha256 });
            entity.HasIndex(item => new { item.ScopeKey, item.SemanticDuplicateKey });
        });

        modelBuilder.Entity<DocumentReviewGroupRecord>(entity =>
        {
            entity.ToTable("document_review_groups");
            entity.HasKey(item => new { item.ScopeKey, item.GroupId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.PeriodKey).HasMaxLength(120).IsRequired();
            entity.Property(item => item.State).HasMaxLength(40).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.ClientId, item.PeriodKey });
        });

        modelBuilder.Entity<DocumentReviewAuditRecord>(entity =>
        {
            entity.ToTable("document_review_audit");
            entity.HasKey(item => new { item.ScopeKey, item.EventId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.Action).HasMaxLength(80).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.TimestampUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.TimestampUtc });
            entity.HasIndex(item => new { item.ScopeKey, item.DocumentId });
            entity.HasIndex(item => new { item.ScopeKey, item.GroupId });
        });

        modelBuilder.Entity<ProcessingBatchRecord>(entity =>
        {
            entity.ToTable("processing_batches");
            entity.HasKey(item => new { item.ScopeKey, item.BatchId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.State).HasMaxLength(40).IsRequired();
            entity.Property(item => item.OperationMode).HasMaxLength(20).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.UpdatedAtUtc });
        });

        modelBuilder.Entity<DispatchItemRecord>(entity =>
        {
            entity.ToTable("dispatch_items");
            entity.HasKey(item => new { item.ScopeKey, item.DispatchItemId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.State).HasMaxLength(40).IsRequired();
            entity.Property(item => item.DispatchFingerprint).HasMaxLength(64);
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.BatchId });
            entity.HasIndex(item => new { item.ScopeKey, item.GroupId });
            entity.HasIndex(item => new { item.ScopeKey, item.DispatchFingerprint });
        });

        modelBuilder.Entity<DeliveryAttemptRecord>(entity =>
        {
            entity.ToTable("delivery_attempts");
            entity.HasKey(item => new { item.ScopeKey, item.AttemptId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.State).HasMaxLength(40).IsRequired();
            entity.Property(item => item.IdempotencyKey).HasMaxLength(180).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.StartedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.DispatchItemId });
            entity.HasIndex(item => new { item.ScopeKey, item.IdempotencyKey }).IsUnique();
        });

        modelBuilder.Entity<DispatchAuditRecord>(entity =>
        {
            entity.ToTable("dispatch_audit");
            entity.HasKey(item => new { item.ScopeKey, item.EventId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.Action).HasMaxLength(80).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.TimestampUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.TimestampUtc });
            entity.HasIndex(item => new { item.ScopeKey, item.DispatchItemId });
        });

        modelBuilder.Entity<WorkspacePreferenceRecord>(entity =>
        {
            entity.ToTable("workspace_preferences");
            entity.HasKey(item => item.Key);
            entity.Property(item => item.Key).HasMaxLength(80);
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
        });

        modelBuilder.Entity<IncidentRecordEntity>(entity =>
        {
            entity.ToTable("incidents");
            entity.HasKey(item => new { item.ScopeKey, item.IncidentId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.Status).HasMaxLength(40).IsRequired();
            entity.Property(item => item.Severity).HasMaxLength(40).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.DetectedAtUtc).HasConversion<long>();
            entity.Property(item => item.UpdatedAtUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.DeliveryAttemptId });
            entity.HasIndex(item => new { item.ScopeKey, item.Status, item.UpdatedAtUtc });
        });

        modelBuilder.Entity<IncidentAuditRecordEntity>(entity =>
        {
            entity.ToTable("incident_audit");
            entity.HasKey(item => new { item.ScopeKey, item.EventId });
            entity.Property(item => item.ScopeKey).HasMaxLength(64);
            entity.Property(item => item.Action).HasMaxLength(80).IsRequired();
            entity.Property(item => item.JsonPayload).IsRequired();
            entity.Property(item => item.TimestampUtc).HasConversion<long>();
            entity.HasIndex(item => new { item.ScopeKey, item.IncidentId, item.TimestampUtc });
        });
    }
}

public sealed class WorkspacePreferenceRecord
{
    public string Key { get; set; } = string.Empty;

    public string JsonPayload { get; set; } = string.Empty;

    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class IncidentRecordEntity
{
    public string ScopeKey { get; set; } = string.Empty;
    public Guid IncidentId { get; set; }
    public Guid DeliveryAttemptId { get; set; }
    public string Status { get; set; } = string.Empty;
    public string Severity { get; set; } = string.Empty;
    public long Version { get; set; }
    public string JsonPayload { get; set; } = string.Empty;
    public DateTimeOffset DetectedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class IncidentAuditRecordEntity
{
    public string ScopeKey { get; set; } = string.Empty;
    public Guid EventId { get; set; }
    public Guid IncidentId { get; set; }
    public string Action { get; set; } = string.Empty;
    public string JsonPayload { get; set; } = string.Empty;
    public DateTimeOffset TimestampUtc { get; set; }
}
