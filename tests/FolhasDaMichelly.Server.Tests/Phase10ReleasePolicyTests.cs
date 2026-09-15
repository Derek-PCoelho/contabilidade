using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Contracts.Updates;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase10ReleasePolicyTests(Phase2WebApplicationFactory factory) :
    IClassFixture<Phase2WebApplicationFactory>
{
    [Fact]
    public async Task ReleasePolicyRequiresAuthentication()
    {
        using var client = factory.CreateClient();

        using var response = await client.GetAsync("/api/app-release-policy?currentVersion=0.12.0");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Theory]
    [InlineData("0.10.9", false)]
    [InlineData("0.12.0", true)]
    [InlineData("0.11.1", false)]
    public async Task ReleasePolicyAppliesGlobalMinimumAndExplicitChannelSwitches(
        string currentVersion,
        bool expectedSupport)
    {
        using var client = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.ClientsRead,
            hasMfa: false);

        var policy = await client.GetFromJsonAsync<AppReleasePolicyResponse>(
            $"/api/app-release-policy?currentVersion={currentVersion}");

        Assert.NotNull(policy);
        Assert.Equal("0.12.0", policy.MinimumSupportedVersion);
        Assert.Equal(expectedSupport, policy.IsSupported);
        Assert.True(policy.EmailSendEnabled);
        Assert.True(policy.BetaChannelEnabled);
        Assert.True(policy.StableChannelEnabled);
    }

    [Theory]
    [InlineData("")]
    [InlineData("versao-invalida")]
    [InlineData("0.12.0%0Aconteudo")]
    public async Task ReleasePolicyRejectsMalformedVersion(string currentVersion)
    {
        using var client = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.ClientsRead,
            hasMfa: false);

        using var response = await client.GetAsync(
            $"/api/app-release-policy?currentVersion={currentVersion}");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
}
