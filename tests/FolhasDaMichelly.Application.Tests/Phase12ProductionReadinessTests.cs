using FolhasDaMichelly.Application.Production;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase12ProductionReadinessTests
{
    [Fact]
    public void ClosedDefaultsFailWithExplicitOperationalBlockers()
    {
        var snapshot = ProductionReadinessEvaluator.Evaluate(
            new ProductionRolloutOptions(),
            pilotModeEnabled: true,
            globalSendEnabled: false,
            stableChannelEnabled: false);

        Assert.False(snapshot.IsReadyForSend);
        Assert.Contains(snapshot.Blockers, blocker => blocker.Contains("fechada", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(snapshot.Blockers, blocker => blocker.Contains("backup", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(snapshot.Blockers, blocker => blocker.Contains("monitoramento", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(snapshot.Blockers, blocker => blocker.Contains("suporte", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void LimitedProductionRequiresOnlyPrivilegedRolesAndEveryFormalGate()
    {
        var options = CreateReadyOptions();
        var snapshot = ProductionReadinessEvaluator.Evaluate(
            options,
            pilotModeEnabled: false,
            globalSendEnabled: true,
            stableChannelEnabled: true);

        Assert.True(snapshot.IsReadyForSend);
        Assert.Empty(snapshot.Blockers);
        Assert.Equal(
            [AppRoles.Administrator, AppRoles.Manager, AppRoles.OwnerTechnical],
            snapshot.AllowedRoles);

        options.AllowedRoles = new HashSet<string>([AppRoles.Manager, AppRoles.Operator], StringComparer.Ordinal);
        snapshot = ProductionReadinessEvaluator.Evaluate(
            options,
            pilotModeEnabled: false,
            globalSendEnabled: true,
            stableChannelEnabled: true);

        Assert.False(snapshot.IsReadyForSend);
        Assert.Contains(snapshot.Blockers, blocker => blocker.Contains("papéis", StringComparison.OrdinalIgnoreCase));
    }

    private static ProductionRolloutOptions CreateReadyOptions() => new()
    {
        Enabled = true,
        EnvironmentName = "production",
        Stage = ProductionRolloutStage.Limited,
        PilotApproved = true,
        AllowSend = true,
        ExternalProviderSendEnabled = true,
        StableReleaseApproved = true,
        BackupRestoreDrillCompleted = true,
        MonitoringReady = true,
        IncidentResponseReady = true,
        SupportReady = true,
        MaximumBatchSize = 5,
        MaximumDailySends = 20,
        MinimumApplicationVersion = "0.12.0",
    };
}
