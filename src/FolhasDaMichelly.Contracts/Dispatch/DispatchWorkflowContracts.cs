using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Contracts.Dispatch;

public enum ProcessingSelectionMode
{
    Individual,
    Batch,
}

public enum DispatchOperationMode
{
    Test,
    Draft,
    Send,
}

public enum FakeDeliveryScenario
{
    Success,
    TransientFailure,
    PermanentFailure,
    Timeout,
    Ambiguous,
}

public enum ProcessingBatchState
{
    Preparing,
    ReadyForReview,
    Approved,
    Processing,
    Completed,
    CompletedWithErrors,
    RecoveryRequired,
    Cancelled,
}

public enum DispatchItemState
{
    Blocked,
    ReadyForApproval,
    Approved,
    DraftCreating,
    DraftCreated,
    Sending,
    AcceptedByProvider,
    Failed,
    Ambiguous,
    Reconciled,
    Completed,
    Cancelled,
}

public enum DeliveryAttemptState
{
    Pending,
    DraftCreated,
    AcceptedByProvider,
    FailedTransient,
    FailedPermanent,
    Ambiguous,
    Reconciled,
}

public sealed record DispatchRecipientSnapshot(
    Guid RecipientId,
    string DisplayName,
    string Email,
    string DeliveryRole,
    Guid? EstablishmentId);

public sealed record DispatchAttachmentSnapshot(
    Guid DocumentId,
    string LocalPath,
    string FileName,
    string Sha256,
    long FileSizeBytes,
    RecognizedDocumentType DocumentType);

public sealed record RenderedMessageSnapshot(
    Guid SubjectTemplateId,
    long SubjectTemplateVersion,
    string SubjectTemplateName,
    Guid BodyTemplateId,
    long BodyTemplateVersion,
    string BodyTemplateName,
    string SenderAccountId,
    IReadOnlyList<DispatchRecipientSnapshot> OriginalTo,
    IReadOnlyList<DispatchRecipientSnapshot> OriginalCc,
    IReadOnlyList<string> EffectiveTo,
    IReadOnlyList<string> EffectiveCc,
    string Subject,
    string TextBody,
    string HtmlBody,
    IReadOnlyList<DispatchAttachmentSnapshot> Attachments,
    string DispatchFingerprint,
    DateTimeOffset RenderedAtUtc);

public sealed record DispatchApprovalSnapshot(
    Guid Id,
    Guid DispatchItemId,
    long ItemRevision,
    Guid ReviewApprovalId,
    string ReviewContentHash,
    string DispatchFingerprint,
    string ApprovedBy,
    DateTimeOffset ApprovedAtUtc);

public sealed record DispatchBlock(
    string Code,
    ValidationSeverity Severity,
    string Message);

public sealed record DispatchItem(
    Guid Id,
    Guid BatchId,
    Guid GroupId,
    Guid ClientId,
    string ClientDisplayName,
    Guid? EstablishmentId,
    string PeriodLabel,
    DispatchOperationMode Mode,
    FakeDeliveryScenario Scenario,
    string? TestDestination,
    DispatchItemState State,
    long Revision,
    RenderedMessageSnapshot? Message,
    DispatchApprovalSnapshot? Approval,
    IReadOnlyList<DispatchBlock> Blocks,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc)
{
    public bool IsApproved => Approval is not null && State == DispatchItemState.Approved;

    public bool PreventsApproval => Blocks.Any(block =>
        block.Severity is ValidationSeverity.Error or ValidationSeverity.Blocker);
}

public sealed record ProcessingBatch(
    Guid Id,
    string ScopeKey,
    ProcessingSelectionMode SelectionMode,
    DispatchOperationMode OperationMode,
    ProcessingBatchState State,
    IReadOnlyList<Guid> GroupIds,
    IReadOnlyList<Guid> DispatchItemIds,
    string CreatedBy,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc)
{
    public int TotalCount => DispatchItemIds.Count;
}

public sealed record DeliveryAttempt(
    Guid Id,
    Guid BatchId,
    Guid DispatchItemId,
    Guid GroupId,
    int AttemptNumber,
    DispatchOperationMode Mode,
    DeliveryAttemptState State,
    string ProviderKey,
    string IdempotencyKey,
    string DispatchFingerprint,
    string? ProviderMessageId,
    string? ProviderDraftId,
    string? ErrorCode,
    string? RedactedError,
    DateTimeOffset StartedAtUtc,
    DateTimeOffset? CompletedAtUtc);

public sealed record DispatchAuditEvent(
    Guid Id,
    string ScopeKey,
    string ActorId,
    DateTimeOffset TimestampUtc,
    string Action,
    Guid? BatchId,
    Guid? DispatchItemId,
    Guid? GroupId,
    string Outcome,
    string? ErrorCode,
    string CorrelationId);

public sealed record DispatchWorkspace(
    string ScopeKey,
    IReadOnlyList<ProcessingBatch> Batches,
    IReadOnlyList<DispatchItem> Items,
    IReadOnlyList<DeliveryAttempt> Attempts,
    IReadOnlyList<DispatchAuditEvent> AuditEvents)
{
    public static DispatchWorkspace Empty(string scopeKey) => new(scopeKey, [], [], [], []);

    public int RecoveryRequiredCount => Attempts.Count(attempt =>
        attempt.State is DeliveryAttemptState.Pending or DeliveryAttemptState.Ambiguous);
}

public sealed record EmailProviderAccount(
    string ProviderKey,
    string AccountId,
    string DisplayName,
    bool IsConnected);

public sealed record EmailProviderCapabilities(
    bool SupportsDrafts,
    bool SupportsSending,
    bool SupportsReconciliation,
    long MaximumAttachmentBytes);

public sealed record EmailEnvelope(
    Guid DispatchItemId,
    string IdempotencyKey,
    DispatchOperationMode OperationMode,
    string DispatchFingerprint,
    string SenderAccountId,
    IReadOnlyList<string> To,
    IReadOnlyList<string> Cc,
    string Subject,
    string TextBody,
    string HtmlBody,
    IReadOnlyList<DispatchAttachmentSnapshot> Attachments,
    FakeDeliveryScenario Scenario);

public sealed record EmailProviderResult(
    DeliveryAttemptState State,
    string? ProviderMessageId,
    string? ProviderDraftId,
    string? ErrorCode,
    string? RedactedError);

public sealed record DispatchReportResult(
    string XlsxPath,
    IReadOnlyList<string> CsvPaths,
    int ItemCount,
    DateTimeOffset ExportedAtUtc,
    string? PdfPath = null);

public sealed record EmailSendPreflightRequest(
    Guid OperationId,
    string ProviderKey,
    string DispatchFingerprint,
    int AttachmentCount,
    string ApplicationVersion,
    DispatchOperationMode OperationMode = DispatchOperationMode.Send,
    int BatchSize = 1);

public sealed record EmailSendPreflightResponse(
    bool Authorized,
    bool EmailSendEnabled,
    string MinimumApplicationVersion,
    Guid CorrelationId,
    string? ErrorCode);
