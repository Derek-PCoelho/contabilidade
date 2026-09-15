using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Contracts.Sync;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Server.Tests;

public sealed class SyncEndpointSecurityTests : IClassFixture<Phase2WebApplicationFactory>
{
    private readonly Phase2WebApplicationFactory factory;

    public SyncEndpointSecurityTests(Phase2WebApplicationFactory factory)
    {
        this.factory = factory;
    }

    [Fact]
    public async Task ApiDerivesTenantFromAuthenticationAndIsolatesOrganizations()
    {
        var firstOrganization = Guid.NewGuid();
        using var firstClient = await CreateClientAsync(
            firstOrganization,
            $"{AppPermissions.ClientsRead},{AppPermissions.ClientsWrite}",
            hasMfa: true);
        var command = new UpsertClientCommand(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "Cliente da organização A",
            true,
            0);

        using var pushed = await firstClient.PostAsJsonAsync(
            "/api/sync/clients",
            new PushSyncRequest([command]));
        Assert.Equal(HttpStatusCode.OK, pushed.StatusCode);

        using var secondClient = await CreateClientAsync(
            Guid.NewGuid(),
            AppPermissions.ClientsRead,
            hasMfa: false);
        var isolated = await secondClient.GetFromJsonAsync<PullSyncResponse>(
            "/api/sync/clients?checkpoint=0");

        Assert.NotNull(isolated);
        Assert.Empty(isolated.Clients);
        Assert.DoesNotContain(
            typeof(UpsertClientCommand).GetProperties(),
            property => property.Name.Contains("Organization", StringComparison.Ordinal));
    }

    [Fact]
    public async Task WriteRequiresMfaAndRevokedDeviceLosesReadAccess()
    {
        var organizationId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var deviceId = Guid.NewGuid();
        using var client = await factory.CreateAuthenticatedClientAsync(
            organizationId,
            userId,
            deviceId,
            $"{AppPermissions.ClientsRead},{AppPermissions.ClientsWrite}",
            hasMfa: false);
        var request = new PushSyncRequest(
        [
            new UpsertClientCommand(
                Guid.NewGuid(),
                Guid.NewGuid(),
                "Operação privilegiada",
                true,
                0),
        ]);

        using var deniedWrite = await client.PostAsJsonAsync("/api/sync/clients", request);
        Assert.Equal(HttpStatusCode.Forbidden, deniedWrite.StatusCode);

        await factory.RevokeDeviceAsync(deviceId);
        using var deniedRead = await client.GetAsync("/api/sync/clients?checkpoint=0");
        Assert.Equal(HttpStatusCode.Forbidden, deniedRead.StatusCode);
    }

    private async Task<HttpClient> CreateClientAsync(
        Guid organizationId,
        string permissions,
        bool hasMfa) =>
        await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            permissions,
            hasMfa);
}
