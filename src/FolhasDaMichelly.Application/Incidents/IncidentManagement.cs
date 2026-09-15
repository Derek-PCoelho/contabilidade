using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Security;

namespace FolhasDaMichelly.Application.Incidents;

public enum IncidentCategory
{
    PotentialWrongRecipient,
    PotentialWrongAttachment,
    DuplicateDelivery,
    AmbiguousProviderResult,
    CredentialExposure,
    LocalDataExposure,
    Other,
}

public enum IncidentSeverity
{
    Low,
    Medium,
    High,
    Critical,
}

public enum IncidentStatus
{
    Open,
    Contained,
    Investigating,
    Resolved,
    Closed,
}

public sealed record IncidentRecord(
    Guid Id,
    string ScopeKey,
    Guid DeliveryAttemptId,
    Guid BatchId,
    Guid DispatchItemId,
    Guid GroupId,
    IncidentCategory Category,
    IncidentSeverity Severity,
    IncidentStatus Status,
    string Summary,
    string? Resolution,
    string DetectedBy,
    DateTimeOffset DetectedAtUtc,
    string UpdatedBy,
    DateTimeOffset UpdatedAtUtc,
    DateTimeOffset? ClosedAtUtc,
    long Version);

public sealed record IncidentAuditEvent(
    Guid Id,
    string ScopeKey,
    Guid IncidentId,
    string ActorId,
    DateTimeOffset TimestampUtc,
    string Action,
    IncidentStatus? PreviousStatus,
    IncidentStatus CurrentStatus,
    string Note);

public sealed record IncidentWorkspace(
    string ScopeKey,
    IReadOnlyList<IncidentRecord> Incidents,
    IReadOnlyList<IncidentAuditEvent> AuditEvents)
{
    public static IncidentWorkspace Empty(string scopeKey) => new(scopeKey, [], []);
}

public sealed record OpenIncidentRequest(
    Guid DeliveryAttemptId,
    IncidentCategory Category,
    IncidentSeverity Severity,
    string Summary);

public sealed record TransitionIncidentRequest(
    Guid IncidentId,
    long ExpectedVersion,
    IncidentStatus Status,
    string Note);

public interface IIncidentStore
{
    Task<IncidentWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken);

    Task SaveAsync(IncidentWorkspace workspace, CancellationToken cancellationToken);
}

public interface IIncidentService
{
    Task<IncidentWorkspace> LoadAsync(CancellationToken cancellationToken);

    Task<IncidentWorkspace> OpenAsync(
        OpenIncidentRequest request,
        CancellationToken cancellationToken);

    Task<IncidentWorkspace> TransitionAsync(
        TransitionIncidentRequest request,
        CancellationToken cancellationToken);
}

public sealed class IncidentService(
    IIncidentStore store,
    IDispatchWorkflowStore dispatchStore,
    IDispatchExecutionContextAccessor contextAccessor,
    ISensitiveTextRedactor redactor,
    IClock clock) : IIncidentService
{
    public async Task<IncidentWorkspace> LoadAsync(CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        return await store.LoadAsync(context.ScopeKey, cancellationToken);
    }

    public async Task<IncidentWorkspace> OpenAsync(
        OpenIncidentRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var dispatch = await dispatchStore.LoadAsync(context.ScopeKey, cancellationToken);
        var attempt = dispatch.Attempts.SingleOrDefault(item => item.Id == request.DeliveryAttemptId)
            ?? throw Error("INCIDENT_ATTEMPT_NOT_FOUND", "Selecione uma tentativa registrada antes de abrir o incidente.");
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        if (workspace.Incidents.Any(item =>
            item.DeliveryAttemptId == attempt.Id &&
            item.Category == request.Category &&
            item.Status != IncidentStatus.Closed))
        {
            throw Error("INCIDENT_ALREADY_OPEN", "Já existe um incidente ativo desta categoria para a tentativa.");
        }

        var summary = RequireText(request.Summary, 20, "INCIDENT_SUMMARY_REQUIRED");
        var now = clock.UtcNow;
        var incident = new IncidentRecord(
            Guid.NewGuid(),
            context.ScopeKey,
            attempt.Id,
            attempt.BatchId,
            attempt.DispatchItemId,
            attempt.GroupId,
            request.Category,
            request.Severity,
            IncidentStatus.Open,
            redactor.Redact(summary),
            null,
            context.ActorId,
            now,
            context.ActorId,
            now,
            null,
            1);
        var audit = new IncidentAuditEvent(
            Guid.NewGuid(),
            context.ScopeKey,
            incident.Id,
            context.ActorId,
            now,
            "incident_opened",
            null,
            IncidentStatus.Open,
            incident.Summary);
        var updated = workspace with
        {
            Incidents = [.. workspace.Incidents, incident],
            AuditEvents = [.. workspace.AuditEvents, audit],
        };
        await store.SaveAsync(updated, cancellationToken);
        return updated;
    }

    public async Task<IncidentWorkspace> TransitionAsync(
        TransitionIncidentRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var incident = workspace.Incidents.SingleOrDefault(item => item.Id == request.IncidentId)
            ?? throw Error("INCIDENT_NOT_FOUND", "Incidente não encontrado neste espaço de trabalho.");
        if (incident.Version != request.ExpectedVersion)
        {
            throw Error("INCIDENT_VERSION_CONFLICT", "O incidente foi alterado; recarregue antes de continuar.");
        }

        if (!CanTransition(incident.Status, request.Status))
        {
            throw Error("INCIDENT_TRANSITION_INVALID", "Esta mudança de situação não é permitida.");
        }

        var minimum = request.Status is IncidentStatus.Resolved or IncidentStatus.Closed ? 10 : 3;
        var note = redactor.Redact(RequireText(request.Note, minimum, "INCIDENT_NOTE_REQUIRED"));
        var now = clock.UtcNow;
        var changed = incident with
        {
            Status = request.Status,
            Resolution = request.Status is IncidentStatus.Resolved or IncidentStatus.Closed
                ? note
                : incident.Resolution,
            UpdatedBy = context.ActorId,
            UpdatedAtUtc = now,
            ClosedAtUtc = request.Status == IncidentStatus.Closed ? now : null,
            Version = incident.Version + 1,
        };
        var audit = new IncidentAuditEvent(
            Guid.NewGuid(),
            context.ScopeKey,
            incident.Id,
            context.ActorId,
            now,
            "incident_status_changed",
            incident.Status,
            changed.Status,
            note);
        var updated = workspace with
        {
            Incidents = workspace.Incidents.Select(item => item.Id == incident.Id ? changed : item).ToArray(),
            AuditEvents = [.. workspace.AuditEvents, audit],
        };
        await store.SaveAsync(updated, cancellationToken);
        return updated;
    }

    private static bool CanTransition(IncidentStatus current, IncidentStatus next) =>
        (current, next) switch
        {
            (IncidentStatus.Open, IncidentStatus.Contained or IncidentStatus.Investigating or IncidentStatus.Resolved) => true,
            (IncidentStatus.Contained, IncidentStatus.Investigating or IncidentStatus.Resolved) => true,
            (IncidentStatus.Investigating, IncidentStatus.Contained or IncidentStatus.Resolved) => true,
            (IncidentStatus.Resolved, IncidentStatus.Closed or IncidentStatus.Investigating) => true,
            _ => false,
        };

    private static string RequireText(string? value, int minimumLength, string code)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length < minimumLength || normalized.Length > 2_000)
        {
            throw Error(code, $"Informe entre {minimumLength} e 2.000 caracteres.");
        }

        return normalized;
    }

    private static IncidentException Error(string code, string message) => new(code, message);
}

public sealed class IncidentException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
