using FolhasDaMichelly.Application.Production;
using FolhasDaMichelly.Application.Updates;

namespace FolhasDaMichelly.Server.Configuration;

public static class ProductionRolloutConfiguration
{
    public static (ProductionRolloutOptions Options, ProductionReadinessSnapshot Snapshot) Read(
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        var options = new ProductionRolloutOptions
        {
            EnforceForExternalSend = true,
            Enabled = configuration.GetValue<bool>("Phase12:Enabled"),
            EnvironmentName = configuration["Phase12:EnvironmentName"]?.Trim() ?? "production",
            Stage = Enum.TryParse<ProductionRolloutStage>(
                configuration["Phase12:Stage"],
                ignoreCase: true,
                out var stage)
                ? stage
                : ProductionRolloutStage.Closed,
            PilotApproved = configuration.GetValue<bool>("Phase12:PilotApproved"),
            AllowSend = configuration.GetValue<bool>("Phase12:AllowSend"),
            ExternalProviderSendEnabled =
                configuration.GetValue<bool>("Phase7:MicrosoftGraph:EmailSendEnabled") ||
                configuration.GetValue<bool>("Phase8:Gmail:EmailSendEnabled"),
            StableReleaseApproved = configuration.GetValue<bool>("Phase12:StableReleaseApproved"),
            BackupRestoreDrillCompleted = configuration.GetValue<bool>("Phase12:BackupRestoreDrillCompleted"),
            MonitoringReady = configuration.GetValue<bool>("Phase12:MonitoringReady"),
            IncidentResponseReady = configuration.GetValue<bool>("Phase12:IncidentResponseReady"),
            SupportReady = configuration.GetValue<bool>("Phase12:SupportReady"),
            MaximumBatchSize = configuration.GetValue<int?>("Phase12:MaximumBatchSize") ?? 5,
            MaximumDailySends = configuration.GetValue<int?>("Phase12:MaximumDailySends") ?? 20,
            MinimumApplicationVersion = configuration["Phase12:MinimumApplicationVersion"] ?? AppVersionInfo.Current,
        };
        var roles = (configuration["Phase12:AllowedRoles"] ?? string.Empty)
            .Split([',', ';'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .ToHashSet(StringComparer.Ordinal);
        if (roles.Count > 0)
        {
            options.AllowedRoles = roles;
        }

        var snapshot = ProductionReadinessEvaluator.Evaluate(
            options,
            configuration.GetValue<bool>("Phase11:Enabled"),
            configuration.GetValue<bool>("Phase10:EmailSendEnabled"),
            configuration.GetValue<bool>("Phase10:StableChannelEnabled"));
        return (options, snapshot);
    }
}
