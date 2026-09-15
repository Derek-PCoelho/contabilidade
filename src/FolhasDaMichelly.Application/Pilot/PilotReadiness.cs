using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;

namespace FolhasDaMichelly.Application.Pilot;

public sealed class PilotModeOptions
{
    public bool Enabled { get; set; }

    public string EnvironmentName { get; set; } = "staging";

    public bool AllowTest { get; set; } = true;

    public bool AllowDraft { get; set; } = true;

    public bool AllowSend { get; set; }

    public bool RequireNonProductionData { get; set; } = true;

    public int MaximumClients { get; set; } = 5;
}

public enum PilotChecklistKey
{
    NonProductionDataConfirmed,
    ControlledAccountConfirmed,
    MacOsStationValidated,
    WindowsStationValidated,
    BackupRestoreValidated,
    RollbackValidated,
}

public sealed record PilotChecklistItem(
    PilotChecklistKey Key,
    bool IsConfirmed,
    string? ConfirmedBy,
    DateTimeOffset? ConfirmedAtUtc);

public sealed record PilotChecklistAuditEvent(
    Guid Id,
    PilotChecklistKey Key,
    bool PreviousValue,
    bool CurrentValue,
    string ActorId,
    DateTimeOffset TimestampUtc);

public sealed record PilotChecklistWorkspace(
    string ScopeKey,
    IReadOnlyList<PilotChecklistItem> Items,
    IReadOnlyList<PilotChecklistAuditEvent> AuditEvents)
{
    public static PilotChecklistWorkspace Empty(string scopeKey) => new(
        scopeKey,
        Enum.GetValues<PilotChecklistKey>()
            .Select(key => new PilotChecklistItem(key, false, null, null))
            .ToArray(),
        []);
}

public sealed record PilotChecklistUpdate(
    bool NonProductionDataConfirmed,
    bool ControlledAccountConfirmed,
    bool MacOsStationValidated,
    bool WindowsStationValidated,
    bool BackupRestoreValidated,
    bool RollbackValidated)
{
    public bool GetValue(PilotChecklistKey key) => key switch
    {
        PilotChecklistKey.NonProductionDataConfirmed => NonProductionDataConfirmed,
        PilotChecklistKey.ControlledAccountConfirmed => ControlledAccountConfirmed,
        PilotChecklistKey.MacOsStationValidated => MacOsStationValidated,
        PilotChecklistKey.WindowsStationValidated => WindowsStationValidated,
        PilotChecklistKey.BackupRestoreValidated => BackupRestoreValidated,
        PilotChecklistKey.RollbackValidated => RollbackValidated,
        _ => false,
    };
}

public sealed record PilotOperationalMetrics(
    int ClientCount,
    int DocumentCount,
    int EligibleDocumentCount,
    int BlockedDocumentCount,
    int DuplicateDocumentCount,
    int TestAttemptCount,
    int DraftAttemptCount,
    int SendAttemptCount,
    int FailedOrAmbiguousAttemptCount,
    int OpenHighOrCriticalIncidentCount)
{
    public static PilotOperationalMetrics Empty { get; } = new(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}

public sealed record PilotReadinessSnapshot(
    bool IsReady,
    int ConfirmedChecklistCount,
    int RequiredChecklistCount,
    PilotChecklistWorkspace Checklist,
    PilotOperationalMetrics Metrics,
    IReadOnlyList<string> Blockers);

public interface IPilotChecklistStore
{
    Task<PilotChecklistWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken);

    Task SaveAsync(PilotChecklistWorkspace workspace, CancellationToken cancellationToken);
}

public interface IPilotReadinessService
{
    Task<PilotReadinessSnapshot> LoadAsync(
        PilotOperationalMetrics metrics,
        CancellationToken cancellationToken);

    Task<PilotReadinessSnapshot> UpdateAsync(
        PilotChecklistUpdate update,
        PilotOperationalMetrics metrics,
        CancellationToken cancellationToken);
}

public sealed class PilotReadinessService(
    IPilotChecklistStore store,
    IDispatchExecutionContextAccessor contextAccessor,
    IClock clock,
    PilotModeOptions options) : IPilotReadinessService
{
    public async Task<PilotReadinessSnapshot> LoadAsync(
        PilotOperationalMetrics metrics,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(metrics);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = Normalize(
            await store.LoadAsync(context.ScopeKey, cancellationToken),
            context.ScopeKey);
        return Evaluate(workspace, metrics);
    }

    public async Task<PilotReadinessSnapshot> UpdateAsync(
        PilotChecklistUpdate update,
        PilotOperationalMetrics metrics,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(update);
        ArgumentNullException.ThrowIfNull(metrics);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = Normalize(
            await store.LoadAsync(context.ScopeKey, cancellationToken),
            context.ScopeKey);
        var items = workspace.Items.ToDictionary(item => item.Key);
        var audit = workspace.AuditEvents.ToList();
        foreach (var key in Enum.GetValues<PilotChecklistKey>())
        {
            var current = items[key];
            var nextValue = update.GetValue(key);
            if (current.IsConfirmed == nextValue)
            {
                continue;
            }

            items[key] = current with
            {
                IsConfirmed = nextValue,
                ConfirmedBy = nextValue ? context.ActorId : null,
                ConfirmedAtUtc = nextValue ? clock.UtcNow : null,
            };
            audit.Add(new PilotChecklistAuditEvent(
                Guid.NewGuid(),
                key,
                current.IsConfirmed,
                nextValue,
                context.ActorId,
                clock.UtcNow));
        }

        var updated = new PilotChecklistWorkspace(
            context.ScopeKey,
            Enum.GetValues<PilotChecklistKey>().Select(key => items[key]).ToArray(),
            audit);
        await store.SaveAsync(updated, cancellationToken);
        return Evaluate(updated, metrics);
    }

    private PilotReadinessSnapshot Evaluate(
        PilotChecklistWorkspace workspace,
        PilotOperationalMetrics metrics)
    {
        var blockers = new List<string>();
        if (!options.Enabled)
        {
            blockers.Add("O modo piloto não está habilitado nesta instalação.");
        }

        if (!string.Equals(options.EnvironmentName, "staging", StringComparison.OrdinalIgnoreCase))
        {
            blockers.Add("O ambiente precisa estar identificado como homologação.");
        }

        if (!options.AllowTest || !options.AllowDraft || options.AllowSend)
        {
            blockers.Add("O piloto exige Teste e Rascunho liberados, com Envio aos clientes bloqueado.");
        }

        if (!options.RequireNonProductionData)
        {
            blockers.Add("A exigência de dados fictícios ou anonimizados precisa permanecer ativa.");
        }

        if (options.MaximumClients is < 1 or > 5)
        {
            blockers.Add("O limite do piloto deve ficar entre 1 e 5 clientes.");
        }
        else if (metrics.ClientCount > options.MaximumClients)
        {
            blockers.Add($"O piloto excedeu o limite de {options.MaximumClients} clientes.");
        }

        if (metrics.SendAttemptCount > 0)
        {
            blockers.Add("Existe tentativa de envio a destinatário registrada; interrompa e investigue.");
        }

        if (metrics.FailedOrAmbiguousAttemptCount > 0)
        {
            blockers.Add("Há operação com falha ou resultado incerto aguardando tratamento.");
        }

        if (metrics.OpenHighOrCriticalIncidentCount > 0)
        {
            blockers.Add("Há ocorrência alta ou crítica ainda não encerrada.");
        }

        var unconfirmed = workspace.Items.Where(item => !item.IsConfirmed).ToArray();
        if (unconfirmed.Length > 0)
        {
            blockers.Add($"Faltam {unconfirmed.Length} confirmações no checklist operacional.");
        }

        return new PilotReadinessSnapshot(
            blockers.Count == 0,
            workspace.Items.Count(item => item.IsConfirmed),
            workspace.Items.Count,
            workspace,
            metrics,
            blockers);
    }

    private static PilotChecklistWorkspace Normalize(
        PilotChecklistWorkspace workspace,
        string scopeKey)
    {
        var existing = workspace.Items
            .GroupBy(item => item.Key)
            .ToDictionary(group => group.Key, group => group.Last());
        var items = Enum.GetValues<PilotChecklistKey>()
            .Select(key => existing.TryGetValue(key, out var item)
                ? item
                : new PilotChecklistItem(key, false, null, null))
            .ToArray();
        return workspace with { ScopeKey = scopeKey, Items = items };
    }
}
