namespace FolhasDaMichelly.Contracts.Documents;

public enum DocumentPeriodKind
{
    Unknown,
    Monthly,
    Annual,
    DateRange,
    EventDate,
    AssessmentPeriod,
}

public enum ValidationSeverity
{
    Info,
    Warning,
    Error,
    Blocker,
}

public enum FindingResolutionType
{
    None,
    ManualOverride,
    Revalidated,
}

public enum ReviewDocumentState
{
    Blocked,
    Ready,
    Grouped,
    Approved,
    Duplicate,
}

public enum ReviewGroupState
{
    Building,
    Blocked,
    ReadyForReview,
    Approved,
}

public sealed record DocumentPeriod(
    DocumentPeriodKind Kind,
    int? Month,
    int? Year,
    DateOnly? StartDate,
    DateOnly? EndDate,
    DateOnly? DueDate,
    string? OriginalText)
{
    public string CanonicalKey => Kind switch
    {
        DocumentPeriodKind.Monthly when Month.HasValue && Year.HasValue =>
            $"month:{Year:0000}-{Month:00}",
        DocumentPeriodKind.Annual when Year.HasValue => $"year:{Year:0000}",
        DocumentPeriodKind.DateRange when StartDate.HasValue && EndDate.HasValue =>
            $"range:{StartDate:yyyy-MM-dd}:{EndDate:yyyy-MM-dd}",
        DocumentPeriodKind.EventDate when StartDate.HasValue => $"event:{StartDate:yyyy-MM-dd}",
        DocumentPeriodKind.AssessmentPeriod when StartDate.HasValue && EndDate.HasValue =>
            $"assessment:{StartDate:yyyy-MM-dd}:{EndDate:yyyy-MM-dd}",
        DocumentPeriodKind.AssessmentPeriod when StartDate.HasValue =>
            $"assessment:{StartDate:yyyy-MM-dd}",
        _ => $"unknown:{OriginalText?.Trim().ToUpperInvariant() ?? string.Empty}",
    };

    public string GroupingPeriodKey => Kind switch
    {
        DocumentPeriodKind.Monthly when Month.HasValue && Year.HasValue =>
            $"{Year:0000}-{Month:00}",
        DocumentPeriodKind.AssessmentPeriod when StartDate.HasValue =>
            $"{StartDate.Value.Year:0000}-{StartDate.Value.Month:00}",
        DocumentPeriodKind.Annual when Year.HasValue => $"{Year:0000}",
        DocumentPeriodKind.DateRange when StartDate.HasValue && EndDate.HasValue =>
            $"{StartDate:yyyy-MM-dd}_{EndDate:yyyy-MM-dd}",
        DocumentPeriodKind.EventDate when StartDate.HasValue => $"{StartDate:yyyy-MM-dd}",
        _ => CanonicalKey,
    };

    public string DisplayLabel => Kind switch
    {
        DocumentPeriodKind.Monthly when Month.HasValue && Year.HasValue =>
            $"{Month:00}/{Year:0000}",
        DocumentPeriodKind.Annual when Year.HasValue => $"{Year:0000}",
        DocumentPeriodKind.DateRange when StartDate.HasValue && EndDate.HasValue =>
            $"{StartDate:dd/MM/yyyy} a {EndDate:dd/MM/yyyy}",
        DocumentPeriodKind.EventDate when StartDate.HasValue => $"{StartDate:dd/MM/yyyy}",
        DocumentPeriodKind.AssessmentPeriod when StartDate.HasValue && EndDate.HasValue =>
            $"{StartDate:dd/MM/yyyy} a {EndDate:dd/MM/yyyy}",
        DocumentPeriodKind.AssessmentPeriod when StartDate.HasValue =>
            $"{StartDate:dd/MM/yyyy}",
        _ => OriginalText ?? "Período não identificado",
    };

    public static DocumentPeriod Unknown(string? originalText = null, DateOnly? dueDate = null) =>
        new(DocumentPeriodKind.Unknown, null, null, null, null, dueDate, originalText);
}

public sealed record ValidationFinding(
    Guid Id,
    string RuleCode,
    ValidationSeverity Severity,
    string Message,
    string? FieldKey,
    bool IsResolved,
    FindingResolutionType ResolutionType,
    string? ResolvedBy,
    string? ResolutionNote,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset? ResolvedAtUtc)
{
    public bool PreventsApproval =>
        !IsResolved && Severity is ValidationSeverity.Error or ValidationSeverity.Blocker;
}

public sealed record ReviewDocument(
    Guid Id,
    string LocalPath,
    string FileName,
    string Sha256,
    long FileSizeBytes,
    int PageCount,
    RecognizedDocumentType DocumentType,
    string ProfileVersion,
    Guid? ClientId,
    Guid? EstablishmentId,
    string? ClientDisplayName,
    string? ClientTaxIdMasked,
    ClientResolutionMethod ResolutionMethod,
    decimal ResolutionConfidence,
    IReadOnlyList<ClientResolutionCandidate> ClientAlternatives,
    IReadOnlyList<string> ResolutionBlockers,
    IReadOnlyList<RecognizedField> Fields,
    IReadOnlyList<RecognitionFinding> RecognitionFindings,
    DocumentPeriod Period,
    string SemanticDuplicateKey,
    ReviewDocumentState State,
    long Revision,
    Guid? GroupId,
    IReadOnlyList<ValidationFinding> Findings,
    DateTimeOffset ImportedAtUtc,
    DateTimeOffset ValidatedAtUtc)
{
    // Non-positional so review payloads written before period correction remain
    // valid JSON and deserialize with a null override.
    public DocumentPeriod? PeriodOverride { get; init; }

    public bool PreventsApproval => Findings.Any(finding => finding.PreventsApproval);

    public int BlockingFindingCount => Findings.Count(finding => finding.PreventsApproval);

    public string ReviewSummary => State switch
    {
        ReviewDocumentState.Duplicate => "Duplicado — aprovação proibida",
        ReviewDocumentState.Blocked => $"Bloqueado — {BlockingFindingCount} pendência(s)",
        ReviewDocumentState.Approved => "Aprovado para preparar a mensagem",
        ReviewDocumentState.Grouped => "Elegível e agrupado",
        _ => "Elegível para agrupamento",
    };
}

public sealed record ApprovalDocumentSnapshot(
    Guid DocumentId,
    string Sha256,
    long Revision,
    Guid ClientId,
    Guid? EstablishmentId,
    string PeriodKey,
    string SemanticDuplicateKey);

public sealed record GroupApprovalSnapshot(
    Guid Id,
    Guid GroupId,
    long GroupRevision,
    string ContentHash,
    string ApprovedBy,
    DateTimeOffset ApprovedAtUtc,
    IReadOnlyList<ApprovalDocumentSnapshot> Documents);

public sealed record DocumentDispatchGroup(
    Guid Id,
    string GroupingKey,
    string GroupingPolicyCode,
    string GroupingPolicyVersion,
    Guid ClientId,
    Guid? EstablishmentId,
    string ClientDisplayName,
    string PeriodKey,
    string PeriodLabel,
    ReviewGroupState State,
    long Revision,
    IReadOnlyList<Guid> DocumentIds,
    IReadOnlyList<ValidationFinding> Findings,
    GroupApprovalSnapshot? ApprovalSnapshot,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc)
{
    public bool IsApproved => ApprovalSnapshot is not null && State == ReviewGroupState.Approved;

    public bool PreventsApproval => Findings.Any(finding => finding.PreventsApproval);

    public string ApprovalSummary => State switch
    {
        ReviewGroupState.Approved => "Aprovado no aplicativo",
        ReviewGroupState.Blocked => "Bloqueado — revisar pendências",
        ReviewGroupState.ReadyForReview => "Pronto para revisão e aprovação",
        _ => "Em formação",
    };
}

public sealed record ReviewAuditEvent(
    Guid Id,
    string ScopeKey,
    string ActorId,
    DateTimeOffset TimestampUtc,
    string Action,
    Guid? DocumentId,
    Guid? GroupId,
    string? PreviousValue,
    string? NewValue,
    string? Reason,
    string CorrelationId);

public sealed record DocumentReviewWorkspace(
    string ScopeKey,
    IReadOnlyList<ReviewDocument> Documents,
    IReadOnlyList<DocumentDispatchGroup> Groups,
    IReadOnlyList<ReviewAuditEvent> AuditEvents)
{
    public static DocumentReviewWorkspace Empty(string scopeKey) => new(scopeKey, [], [], []);

    public int EligibleDocumentCount => Documents.Count(document =>
        document.State is ReviewDocumentState.Ready or ReviewDocumentState.Grouped or ReviewDocumentState.Approved);

    public int BlockedDocumentCount => Documents.Count(document =>
        document.State is ReviewDocumentState.Blocked or ReviewDocumentState.Duplicate);

    public int ApprovedGroupCount => Groups.Count(group => group.IsApproved);
}
