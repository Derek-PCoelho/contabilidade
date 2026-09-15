using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Documents;

public sealed record DocumentReviewContext(string ScopeKey, string ActorId);

public sealed record DocumentReviewProfile(
    RecognizedDocumentType DocumentType,
    string Version,
    IReadOnlySet<SemanticFieldRole> RequiredRoles,
    IReadOnlyList<SemanticFieldRole> SemanticIdentityRoles,
    string GroupingPolicyCode,
    bool IncludeEstablishmentInGrouping,
    bool GroupIndividually,
    bool RequirePeriod,
    bool RequirePositiveAmount);

public sealed class DocumentReviewOptions
{
    public const string PolicyVersion = "phase5-review-v1";

    public DocumentReviewOptions()
    {
        Profiles = CreateDefaultProfiles();
    }

    public IReadOnlyDictionary<RecognizedDocumentType, DocumentReviewProfile> Profiles { get; set; }

    public int MinimumOverrideReasonLength { get; set; } = 10;

    public bool PastDueDateProducesWarning { get; set; } = true;

    private static Dictionary<RecognizedDocumentType, DocumentReviewProfile> CreateDefaultProfiles()
    {
        var clientAndAmount = new HashSet<SemanticFieldRole>
        {
            SemanticFieldRole.EmployerTaxId,
            SemanticFieldRole.TotalAmount,
        };
        var federalClientAndAmount = new HashSet<SemanticFieldRole>
        {
            SemanticFieldRole.ClientTaxId,
            SemanticFieldRole.TotalAmount,
        };
        return new Dictionary<RecognizedDocumentType, DocumentReviewProfile>
        {
            [RecognizedDocumentType.Payroll] = new(
                RecognizedDocumentType.Payroll,
                PolicyVersion,
                clientAndAmount,
                [],
                "monthly-accounting",
                true,
                false,
                true,
                true),
            [RecognizedDocumentType.FgtsDigital] = new(
                RecognizedDocumentType.FgtsDigital,
                PolicyVersion,
                clientAndAmount,
                [],
                "monthly-accounting",
                true,
                false,
                true,
                true),
            [RecognizedDocumentType.FederalRevenueCollection] = new(
                RecognizedDocumentType.FederalRevenueCollection,
                PolicyVersion,
                federalClientAndAmount,
                [],
                "monthly-accounting",
                true,
                false,
                true,
                true),
            [RecognizedDocumentType.ThirteenthSalary] = new(
                RecognizedDocumentType.ThirteenthSalary,
                PolicyVersion,
                clientAndAmount,
                [SemanticFieldRole.EmployeeCpf],
                "monthly-accounting",
                true,
                false,
                true,
                true),
            [RecognizedDocumentType.ProLabore] = new(
                RecognizedDocumentType.ProLabore,
                PolicyVersion,
                clientAndAmount,
                [SemanticFieldRole.PartnerCpf],
                "monthly-accounting",
                true,
                false,
                true,
                true),
            [RecognizedDocumentType.Vacation] = new(
                RecognizedDocumentType.Vacation,
                PolicyVersion,
                clientAndAmount,
                [SemanticFieldRole.EmployeeCpf],
                "vacation-event",
                true,
                true,
                true,
                true),
            [RecognizedDocumentType.Termination] = new(
                RecognizedDocumentType.Termination,
                PolicyVersion,
                clientAndAmount,
                [SemanticFieldRole.EmployeeCpf],
                "termination-event",
                true,
                true,
                true,
                true),
        };
    }
}

public sealed record DocumentValidationContext(
    ReviewDocument Document,
    DocumentReviewProfile? Profile,
    DateOnly AccountingDate,
    DateTimeOffset EvaluatedAtUtc);

public interface IValidationRule<in T>
{
    string Code { get; }

    Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(
        T context,
        CancellationToken cancellationToken);
}

public interface IDocumentPeriodParser
{
    DocumentPeriod Parse(IReadOnlyList<RecognizedField> fields);
}

public interface IDocumentReviewContextAccessor
{
    Task<DocumentReviewContext> GetCurrentAsync(CancellationToken cancellationToken);
}

public interface IDocumentReviewStore
{
    Task<DocumentReviewWorkspace> LoadAsync(
        string scopeKey,
        CancellationToken cancellationToken);

    Task SaveAsync(
        DocumentReviewWorkspace workspace,
        CancellationToken cancellationToken);
}

public interface IDocumentReviewService
{
    Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> ImportAsync(
        string localPath,
        DocumentRecognitionResult recognition,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> CorrectPeriodAsync(
        Guid documentId,
        DocumentPeriod period,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(
        Guid documentId,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> RemoveDocumentAsync(
        Guid documentId,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> OverrideClientAsync(
        Guid documentId,
        ClientResolutionCandidate candidate,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> SplitGroupAsync(
        Guid groupId,
        IReadOnlyCollection<Guid> documentIds,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> MergeGroupsAsync(
        Guid targetGroupId,
        Guid sourceGroupId,
        string reason,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> ApproveGroupAsync(
        Guid groupId,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> ApproveGroupsAsync(
        IReadOnlyCollection<Guid> groupIds,
        CancellationToken cancellationToken);

    Task<DocumentReviewWorkspace> ApproveClientGroupsAsync(
        Guid clientId,
        IReadOnlyCollection<Guid> groupIds,
        CancellationToken cancellationToken) => throw new NotSupportedException(
            "Esta implementação não oferece liberação atômica por cliente.");

    Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken);
}

public sealed class DocumentReviewException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
