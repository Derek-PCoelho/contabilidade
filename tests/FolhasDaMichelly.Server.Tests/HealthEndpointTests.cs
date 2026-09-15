using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Contracts;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace FolhasDaMichelly.Server.Tests;

public sealed class HealthEndpointTests : IClassFixture<Phase2WebApplicationFactory>
{
    private readonly HttpClient client;

    public HealthEndpointTests(Phase2WebApplicationFactory factory)
    {
        client = factory.CreateClient();
    }

    [Fact]
    public async Task LiveHealthEndpointReturnsOk()
    {
        using var response = await client.GetAsync("/health/live", CancellationToken.None);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task RootEndpointReturnsNonSensitivePhaseStatus()
    {
        var status = await client.GetFromJsonAsync<ServiceStatusResponse>(
            "/",
            CancellationToken.None);

        Assert.NotNull(status);
        Assert.Equal("Folhas da Michelly API", status.Service);
        Assert.Equal("Phase12GradualProduction", status.Status);
    }

    [Fact]
    public async Task SyncEndpointRequiresAuthentication()
    {
        using var response = await client.GetAsync(
            "/api/sync/clients?checkpoint=0",
            CancellationToken.None);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }
}

public sealed class Phase2WebApplicationFactory : WebApplicationFactory<Program>
{
    private readonly string databasePath = Path.Combine(
        Path.GetTempPath(),
        $"folhas-server-tests-{Guid.NewGuid():N}.db");

    public async Task<HttpClient> CreateAuthenticatedClientAsync(
        Guid organizationId,
        Guid userId,
        Guid deviceId,
        string permissions,
        bool hasMfa,
        string roles = AppRoles.Manager)
    {
        var authenticatedClient = CreateClient();
        await using var scope = Services.CreateAsyncScope();
        var context = scope.ServiceProvider.GetRequiredService<FolhasDbContext>();
        await context.Database.EnsureCreatedAsync();
        if (!await context.DeviceSessions.AnyAsync(item => item.Id == deviceId))
        {
            context.DeviceSessions.Add(
                new DeviceSession
                {
                    Id = deviceId,
                    OrganizationId = organizationId,
                    UserId = userId,
                    DeviceName = "Synthetic test device",
                    CreatedAtUtc = DateTimeOffset.UtcNow,
                    LastSeenAtUtc = DateTimeOffset.UtcNow,
                });
            await context.SaveChangesAsync();
        }

        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-User",
            userId.ToString("D"));
        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-Organization",
            organizationId.ToString("D"));
        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-Device",
            deviceId.ToString("D"));
        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-Permissions",
            permissions);
        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-Mfa",
            hasMfa.ToString());
        authenticatedClient.DefaultRequestHeaders.Add(
            "X-Development-Roles",
            roles);
        return authenticatedClient;
    }

    public async Task RevokeDeviceAsync(Guid deviceId)
    {
        await using var scope = Services.CreateAsyncScope();
        var context = scope.ServiceProvider.GetRequiredService<FolhasDbContext>();
        var device = await context.DeviceSessions.SingleAsync(item => item.Id == deviceId);
        device.RevokedAtUtc = DateTimeOffset.UtcNow;
        await context.SaveChangesAsync();
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);
        builder.UseEnvironment("Testing");
        builder.UseSetting("Database:Provider", "Sqlite");
        builder.UseSetting("Database:Initialize", "false");
        builder.UseSetting(
            "ConnectionStrings:CentralDatabase",
            $"Data Source={databasePath}");
        builder.UseSetting("Authentication:EnableDevelopmentScheme", "true");
        builder.UseSetting("Phase7:MicrosoftGraph:EmailSendEnabled", "true");
        builder.UseSetting("Phase7:MinimumSendVersion", "0.7.0");
        builder.UseSetting("Phase8:Gmail:EmailSendEnabled", "true");
        builder.UseSetting("Phase8:MinimumSendVersion", "0.8.0");
        builder.UseSetting("Phase10:MinimumSupportedVersion", "0.12.0");
        builder.UseSetting("Phase10:EmailSendEnabled", "true");
        builder.UseSetting("Phase10:BetaChannelEnabled", "true");
        builder.UseSetting("Phase10:StableChannelEnabled", "true");
        builder.UseSetting("Phase11:Enabled", "false");
        builder.UseSetting("Phase11:EnvironmentName", "staging");
        builder.UseSetting("Phase11:AllowTest", "true");
        builder.UseSetting("Phase11:AllowDraft", "true");
        builder.UseSetting("Phase11:AllowSend", "true");
        builder.UseSetting("Phase11:RequireNonProductionData", "true");
        builder.UseSetting("Phase11:MaximumClients", "5");
        builder.UseSetting("Phase12:Enabled", "true");
        builder.UseSetting("Phase12:EnvironmentName", "production");
        builder.UseSetting("Phase12:Stage", "Limited");
        builder.UseSetting("Phase12:PilotApproved", "true");
        builder.UseSetting("Phase12:AllowSend", "true");
        builder.UseSetting("Phase12:StableReleaseApproved", "true");
        builder.UseSetting("Phase12:BackupRestoreDrillCompleted", "true");
        builder.UseSetting("Phase12:MonitoringReady", "true");
        builder.UseSetting("Phase12:IncidentResponseReady", "true");
        builder.UseSetting("Phase12:SupportReady", "true");
        builder.UseSetting("Phase12:MaximumBatchSize", "5");
        builder.UseSetting("Phase12:MaximumDailySends", "20");
        builder.UseSetting("Phase12:MinimumApplicationVersion", "0.12.0");
        builder.UseSetting("Phase12:AllowedRoles", "OwnerTechnical,Administrator,Manager");
    }

}
