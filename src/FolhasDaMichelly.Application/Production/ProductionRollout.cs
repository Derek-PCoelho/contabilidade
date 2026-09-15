using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Production;

public enum ProductionRolloutStage
{
    Closed,
    Limited,
    Gradual,
}

public sealed class ProductionRolloutOptions
{
    public bool EnforceForExternalSend { get; set; } = true;

    public bool Enabled { get; set; }

    public string EnvironmentName { get; set; } = "production";

    public ProductionRolloutStage Stage { get; set; } = ProductionRolloutStage.Closed;

    public bool PilotApproved { get; set; }

    public bool AllowSend { get; set; }

    public bool ExternalProviderSendEnabled { get; set; }

    public bool StableReleaseApproved { get; set; }

    public bool BackupRestoreDrillCompleted { get; set; }

    public bool MonitoringReady { get; set; }

    public bool IncidentResponseReady { get; set; }

    public bool SupportReady { get; set; }

    public int MaximumBatchSize { get; set; } = 5;

    public int MaximumDailySends { get; set; } = 20;

    public string MinimumApplicationVersion { get; set; } = "0.12.0";

    public IReadOnlySet<string> AllowedRoles { get; set; } = new HashSet<string>(
        [AppRoles.OwnerTechnical, AppRoles.Administrator, AppRoles.Manager],
        StringComparer.Ordinal);
}

public sealed record ProductionReadinessSnapshot(
    bool IsReadyForSend,
    ProductionRolloutStage Stage,
    int MaximumBatchSize,
    int MaximumDailySends,
    string MinimumApplicationVersion,
    IReadOnlyList<string> AllowedRoles,
    IReadOnlyList<string> Blockers);

public static class ProductionReadinessEvaluator
{
    private static readonly HashSet<string> PrivilegedRoles = new(
        [AppRoles.OwnerTechnical, AppRoles.Administrator, AppRoles.Manager],
        StringComparer.Ordinal);

    public static ProductionReadinessSnapshot Evaluate(
        ProductionRolloutOptions options,
        bool pilotModeEnabled,
        bool globalSendEnabled,
        bool stableChannelEnabled)
    {
        ArgumentNullException.ThrowIfNull(options);
        var blockers = new List<string>();
        if (!options.EnforceForExternalSend)
        {
            blockers.Add("A proteção obrigatória da produção gradual está desativada.");
        }

        if (!options.Enabled)
        {
            blockers.Add("A produção gradual ainda não foi habilitada.");
        }

        if (!string.Equals(options.EnvironmentName, "production", StringComparison.OrdinalIgnoreCase))
        {
            blockers.Add("O ambiente precisa estar identificado como produção.");
        }

        if (options.Stage == ProductionRolloutStage.Closed)
        {
            blockers.Add("A etapa de produção permanece fechada.");
        }

        if (pilotModeEnabled || !options.PilotApproved)
        {
            blockers.Add("O piloto supervisionado ainda não possui aceite operacional formal.");
        }

        if (!options.AllowSend || !globalSendEnabled)
        {
            blockers.Add("O kill switch de envio permanece desligado.");
        }

        if (!options.ExternalProviderSendEnabled)
        {
            blockers.Add("Nenhum provedor externo possui envio habilitado.");
        }

        if (!options.StableReleaseApproved || !stableChannelEnabled)
        {
            blockers.Add("A versão stable assinada ainda não foi aprovada e publicada.");
        }

        if (!options.BackupRestoreDrillCompleted)
        {
            blockers.Add("O exercício de backup e restauração ainda não foi concluído.");
        }

        if (!options.MonitoringReady)
        {
            blockers.Add("Monitoramento e alertas operacionais ainda não estão prontos.");
        }

        if (!options.IncidentResponseReady)
        {
            blockers.Add("A resposta a incidentes ainda não foi validada.");
        }

        if (!options.SupportReady)
        {
            blockers.Add("Responsáveis e canal de suporte ainda não foram confirmados.");
        }

        if (options.MaximumBatchSize is < 1 or > 25)
        {
            blockers.Add("O limite por lote deve ficar entre 1 e 25 mensagens.");
        }

        if (options.MaximumDailySends is < 1 or > 500)
        {
            blockers.Add("O limite diário deve ficar entre 1 e 500 mensagens.");
        }

        if (!Version.TryParse(options.MinimumApplicationVersion, out _))
        {
            blockers.Add("A versão mínima da produção é inválida.");
        }

        var allowedRoles = options.AllowedRoles
            .Where(PrivilegedRoles.Contains)
            .Distinct(StringComparer.Ordinal)
            .Order(StringComparer.Ordinal)
            .ToArray();
        if (allowedRoles.Length == 0 || options.AllowedRoles.Any(role => !PrivilegedRoles.Contains(role)))
        {
            blockers.Add("Os papéis autorizados devem ser somente Gestor, Administrador ou Proprietário técnico.");
        }

        return new ProductionReadinessSnapshot(
            blockers.Count == 0,
            options.Stage,
            options.MaximumBatchSize,
            options.MaximumDailySends,
            options.MinimumApplicationVersion,
            allowedRoles,
            blockers);
    }
}
