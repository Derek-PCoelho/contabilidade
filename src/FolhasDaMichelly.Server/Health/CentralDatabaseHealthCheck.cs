using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace FolhasDaMichelly.Server.Health;

public sealed class CentralDatabaseHealthCheck(FolhasDbContext dbContext) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(
        HealthCheckContext context,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(context);
        return await dbContext.Database.CanConnectAsync(cancellationToken)
            ? HealthCheckResult.Healthy("Central database is reachable.")
            : HealthCheckResult.Unhealthy("Central database is unreachable.");
    }
}
