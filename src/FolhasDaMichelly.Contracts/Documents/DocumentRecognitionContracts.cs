namespace FolhasDaMichelly.Contracts.Documents;

public enum RecognizedDocumentType
{
    Unclassified,
    Vacation,
    FgtsDigital,
    Payroll,
    FederalRevenueCollection,
    ThirteenthSalary,
    ProLabore,
    Termination,
}

public enum SemanticFieldRole
{
    Unknown,
    EmployerTaxId,
    ClientTaxId,
    EstablishmentTaxId,
    EmployeeCpf,
    UnionTaxId,
    DocumentIssuerTaxId,
    PartnerCpf,
    EmployerName,
    ClientName,
    EmployeeName,
    UnionName,
    InternalCode,
    Competence,
    AssessmentPeriod,
    DueDate,
    TotalAmount,
    VacationPeriod,
    EventDate,
}

public enum RecognitionConfidence
{
    Low,
    Medium,
    High,
}

public enum ClientResolutionMethod
{
    None,
    ExactClientTaxId,
    ExactEstablishmentTaxId,
    UniqueCnpjRootAndName,
    ExactIndividualTaxId,
    ExactInternalCode,
    ExactLegalName,
    ExactAlias,
    FuzzySuggestion,
    ManualOverride,
}

public sealed record EvidenceBox(
    int PageNumber,
    decimal X,
    decimal Y,
    decimal Width,
    decimal Height,
    string Snippet);

public sealed record RecognizedField(
    string Name,
    string Value,
    string DisplayValue,
    SemanticFieldRole Role,
    decimal Confidence,
    EvidenceBox Evidence);

public sealed record RecognitionFinding(
    string Code,
    string Message,
    bool IsBlocker);

public sealed record ClientResolutionCandidate(
    Guid ClientId,
    Guid? EstablishmentId,
    string DisplayName,
    string TaxIdMasked,
    ClientResolutionMethod Method,
    decimal Confidence);

public sealed record ClientResolutionRequest(
    IReadOnlyList<RecognizedField> Fields);

public sealed record ClientResolutionResult(
    Guid? ClientId,
    Guid? EstablishmentId,
    string? ClientDisplayName,
    string? ClientTaxIdMasked,
    ClientResolutionMethod Method,
    decimal Confidence,
    IReadOnlyList<EvidenceBox> Evidence,
    IReadOnlyList<ClientResolutionCandidate> Alternatives,
    IReadOnlyList<string> Blockers)
{
    public bool IsResolved => ClientId.HasValue && Blockers.Count == 0;

    public static ClientResolutionResult Unresolved(params string[] blockers) => new(
        null,
        null,
        null,
        null,
        ClientResolutionMethod.None,
        0m,
        [],
        [],
        blockers);
}

public sealed record DocumentRecognitionResult(
    string FileName,
    string Sha256,
    string MimeType,
    long FileSizeBytes,
    int PageCount,
    RecognizedDocumentType DocumentType,
    string ProfileVersion,
    RecognitionConfidence Confidence,
    decimal ConfidenceScore,
    bool NeedsOcr,
    bool FromCache,
    IReadOnlyList<RecognizedField> Fields,
    ClientResolutionResult Resolution,
    IReadOnlyList<RecognitionFinding> Findings);
