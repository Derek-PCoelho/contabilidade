using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Pilot;
using FolhasDaMichelly.Domain.Identity;
using Microsoft.AspNetCore.Hosting;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase11PilotPolicyTests(Phase2WebApplicationFactory factory) :
    IClassFixture<Phase2WebApplicationFactory>
{
    [Fact]
    public async Task PilotPolicyRequiresAuthentication()
    {
        using var client = factory.CreateClient();

        using var response = await client.GetAsync("/api/pilot/policy");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task StagingPolicyExposesSafePilotLimitsWithoutSecrets()
    {
        using var authenticated = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.ClientsRead,
            hasMfa: false);
        using var pilotFactory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Phase11:Enabled", "true");
            builder.UseSetting("Phase11:AllowSend", "false");
        });
        using var client = pilotFactory.CreateClient();
        CopyHeaders(authenticated, client);

        var policy = await client.GetFromJsonAsync<PilotPolicyResponse>("/api/pilot/policy");

        Assert.NotNull(policy);
        Assert.True(policy.Enabled);
        Assert.Equal("staging", policy.EnvironmentName);
        Assert.True(policy.AllowTest);
        Assert.True(policy.AllowDraft);
        Assert.False(policy.AllowSend);
        Assert.True(policy.RequireNonProductionData);
        Assert.Equal(5, policy.MaximumClients);
        Assert.Equal("0.12.0", policy.MinimumApplicationVersion);
    }

    [Fact]
    public async Task PilotSendSwitchBlocksCentralPreflightEvenWhenOlderSwitchesAreOpen()
    {
        using var authenticated = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);
        using var pilotFactory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Phase11:Enabled", "true");
            builder.UseSetting("Phase11:AllowSend", "false");
        });
        using var client = pilotFactory.CreateClient();
        CopyHeaders(authenticated, client);

        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            new EmailSendPreflightRequest(
                Guid.NewGuid(),
                DispatchWorkflowOptions.GmailProviderKey,
                new string('e', 64),
                1,
                DispatchWorkflowOptions.CurrentApplicationVersion,
                DispatchOperationMode.Send));
        var result = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(result);
        Assert.False(result.Authorized);
        Assert.False(result.EmailSendEnabled);
        Assert.Equal("PILOT_SEND_DISABLED", result.ErrorCode);

        using var testResponse = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            new EmailSendPreflightRequest(
                Guid.NewGuid(),
                DispatchWorkflowOptions.GmailProviderKey,
                new string('f', 64),
                1,
                DispatchWorkflowOptions.CurrentApplicationVersion,
                DispatchOperationMode.Test));
        var testResult = await testResponse.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();
        Assert.Equal(HttpStatusCode.OK, testResponse.StatusCode);
        Assert.NotNull(testResult);
        Assert.True(testResult.Authorized);
        Assert.True(testResult.EmailSendEnabled);
        Assert.Null(testResult.ErrorCode);
    }

    [Fact]
    public async Task InvalidPilotEnvironmentFailsClosed()
    {
        using var authenticated = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.ClientsRead,
            hasMfa: false);
        using var invalidFactory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Phase11:Enabled", "true");
            builder.UseSetting("Phase11:EnvironmentName", "production");
        });
        using var client = invalidFactory.CreateClient();
        CopyHeaders(authenticated, client);

        using var response = await client.GetAsync("/api/pilot/policy");

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    private static void CopyHeaders(HttpClient source, HttpClient destination)
    {
        foreach (var header in source.DefaultRequestHeaders)
        {
            destination.DefaultRequestHeaders.TryAddWithoutValidation(header.Key, header.Value);
        }
    }
}
