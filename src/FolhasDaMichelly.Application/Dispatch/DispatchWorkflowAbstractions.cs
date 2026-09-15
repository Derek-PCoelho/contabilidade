using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Dispatch;

public sealed record DispatchExecutionContext(
    string ScopeKey,
    string ActorId,
    IReadOnlySet<string> Permissions);

public sealed record PrepareDispatchRequest(
    IReadOnlyList<Guid> GroupIds,
    ProcessingSelectionMode SelectionMode,
    DispatchOperationMode OperationMode,
    string? TestDestination,
    FakeDeliveryScenario Scenario);

public sealed record ExecuteDispatchRequest(
    Guid DispatchItemId,
    string? ConfirmationPhrase);

public sealed class DispatchWorkflowOptions
{
    public const string FakeProviderKey = "fake.local";
    public const string MicrosoftGraphProviderKey = "microsoft.graph";
    public const string GmailProviderKey = "google.gmail";
    public const string DefaultOfficeName = "AL Contadores Associados";
    public static string CurrentApplicationVersion => AppVersionInfo.Current;

    public bool EmailSendEnabled { get; set; } = true;

    public bool PilotModeEnabled { get; set; }

    public bool PilotAllowTest { get; set; } = true;

    public bool PilotAllowDraft { get; set; } = true;

    public bool PilotAllowSend { get; set; }

    public bool ProductionRolloutEnforced { get; set; } = true;

    public bool ProductionRolloutReady { get; set; }

    public int ProductionMaximumBatchSize { get; set; } = 5;

    public string MinimumSendVersion { get; set; } = CurrentApplicationVersion;

    public string ProviderKey { get; set; } = FakeProviderKey;

    public string SenderAccountId { get; set; } = "fake://local";

    public bool MicrosoftGraphEnabled { get; set; }

    public bool MicrosoftGraphSendEnabled { get; set; }

    public string? MicrosoftGraphControlledRecipient { get; set; }

    public bool GmailEnabled { get; set; }

    public bool GmailSendEnabled { get; set; }

    public string? GmailControlledRecipient { get; set; }

    public string OfficeName { get; set; } = DefaultOfficeName;

    public long MaximumAttachmentBytes { get; set; } = 25L * 1024L * 1024L;
}

public sealed record EmailAccountConnectionStatus(
    string ProviderKey,
    bool IsConfigured,
    bool IsConnected,
    string? AccountId,
    string DisplayName,
    string? ErrorCode);

public interface IEmailAccountSession
{
    string ProviderKey { get; }

    Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken);

    Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken);

    Task DisconnectAsync(CancellationToken cancellationToken);

    Task<string> GetAccessTokenAsync(
        bool requireSendPermission,
        CancellationToken cancellationToken);
}

public interface IEmailAccountConnectionService
{
    Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken);

    Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken);

    Task DisconnectAsync(CancellationToken cancellationToken);
}

public interface IDispatchExecutionContextAccessor
{
    Task<DispatchExecutionContext> GetCurrentAsync(CancellationToken cancellationToken);
}

public interface IDispatchWorkflowStore
{
    Task<DispatchWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken);

    Task SaveAsync(DispatchWorkspace workspace, CancellationToken cancellationToken);
}

public interface IEmailProvider
{
    string ProviderKey { get; }

    Task<EmailProviderAccount> GetAccountAsync(CancellationToken cancellationToken);

    Task<EmailProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken);

    Task<EmailProviderResult> CreateDraftAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken);

    Task<EmailProviderResult> SendAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken);

    Task<EmailProviderResult> ReconcileAsync(
        DeliveryAttempt attempt,
        CancellationToken cancellationToken);
}

public interface IDispatchMessageComposer
{
    Task<(RenderedMessageSnapshot? Message, IReadOnlyList<DispatchBlock> Blocks)> ComposeAsync(
        DocumentDispatchGroup group,
        IReadOnlyList<ReviewDocument> documents,
        DispatchOperationMode mode,
        string? testDestination,
        CancellationToken cancellationToken);
}

public interface IDispatchWorkflowService
{
    Task<DispatchWorkspace> LoadAsync(CancellationToken cancellationToken);

    Task<DispatchWorkspace> PrepareAsync(
        PrepareDispatchRequest request,
        CancellationToken cancellationToken);

    Task<DispatchWorkspace> ApproveAsync(
        Guid dispatchItemId,
        CancellationToken cancellationToken);

    Task<DispatchWorkspace> ApproveBatchAsync(
        Guid batchId,
        CancellationToken cancellationToken);

    Task<DispatchWorkspace> ExecuteAsync(
        ExecuteDispatchRequest request,
        CancellationToken cancellationToken);

    Task<DispatchWorkspace> ExecuteBatchAsync(
        Guid batchId,
        string? confirmationPhrase,
        CancellationToken cancellationToken);

    Task<DispatchWorkspace> ReconcileAsync(
        Guid dispatchItemId,
        CancellationToken cancellationToken);

    Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        CancellationToken cancellationToken);

    Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        int? year,
        int? month,
        CancellationToken cancellationToken) => throw new NotSupportedException(
            "Esta implementação não oferece recortes de relatório por ano e mês.");

    Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        DispatchReportFilter filter,
        CancellationToken cancellationToken) => throw new NotSupportedException(
            "Esta implementação não oferece filtros combináveis de relatório.");
}

public interface IDispatchReportExporter
{
    Task<DispatchReportResult> ExportAsync(
        DispatchWorkspace dispatchWorkspace,
        DocumentReviewWorkspace reviewWorkspace,
        string directory,
        CancellationToken cancellationToken);

    Task<DispatchReportResult> ExportAsync(
        DispatchWorkspace dispatchWorkspace,
        DocumentReviewWorkspace reviewWorkspace,
        DispatchReportFilter filter,
        string directory,
        CancellationToken cancellationToken) => throw new NotSupportedException(
            "Esta implementação não oferece filtros combináveis de relatório.");
}

public enum DispatchReportScope
{
    AllPeriods,
    Month,
    Client,
    Year,
    Range,
}

public sealed record DispatchReportFilter(
    DispatchReportScope Scope,
    int? Year = null,
    int? Month = null,
    Guid? ClientId = null,
    string? ClientDisplayName = null,
    int? StartYear = null,
    int? StartMonth = null,
    int? EndYear = null,
    int? EndMonth = null)
{
    public static DispatchReportFilter AllPeriods { get; } = new(DispatchReportScope.AllPeriods);

    public static DispatchReportFilter ForMonth(int year, int month) =>
        new(DispatchReportScope.Month, year, month);

    public static DispatchReportFilter ForYear(int year) =>
        new(DispatchReportScope.Year, year);

    public static DispatchReportFilter ForRange(
        int startYear,
        int startMonth,
        int endYear,
        int endMonth) =>
        new(
            DispatchReportScope.Range,
            StartYear: startYear,
            StartMonth: startMonth,
            EndYear: endYear,
            EndMonth: endMonth);

    public static DispatchReportFilter ForClient(Guid clientId, string? clientDisplayName = null) =>
        new(DispatchReportScope.Client, ClientId: clientId, ClientDisplayName: clientDisplayName);

    public static DispatchReportFilter ForClientInMonth(
        Guid clientId,
        int year,
        int month,
        string? clientDisplayName = null) =>
        ForMonth(year, month).WithClient(clientId, clientDisplayName);

    public static DispatchReportFilter ForClientInYear(
        Guid clientId,
        int year,
        string? clientDisplayName = null) =>
        ForYear(year).WithClient(clientId, clientDisplayName);

    public static DispatchReportFilter ForClientInRange(
        Guid clientId,
        int startYear,
        int startMonth,
        int endYear,
        int endMonth,
        string? clientDisplayName = null) =>
        ForRange(startYear, startMonth, endYear, endMonth)
            .WithClient(clientId, clientDisplayName);

    public DispatchReportFilter WithClient(Guid clientId, string? clientDisplayName = null) =>
        this with { ClientId = clientId, ClientDisplayName = clientDisplayName };
}

public interface IRemoteEmailSendGuard
{
    Task<EmailSendPreflightResponse> AuthorizeAsync(
        EmailSendPreflightRequest request,
        CancellationToken cancellationToken);
}

public sealed class EmailAccountSessionException(
    string code,
    string message,
    Exception? innerException = null) : Exception(message, innerException)
{
    public string Code { get; } = code;
}

public sealed class DispatchWorkflowException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
