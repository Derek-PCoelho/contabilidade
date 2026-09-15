using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Production;
using FolhasDaMichelly.Domain.Identity;
using Microsoft.AspNetCore.Hosting;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase12ProductionPolicyTests(Phase2WebApplicationFactory factory) :
    IClassFixture<Phase2WebApplicationFactory>
{
    [Fact]
    public async Task ProductionPolicyRequiresAuthentication()
    {
        using var client = factory.CreateClient();

        using var response = await client.GetAsync("/api/production/policy");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task ClosedProductionReportsBlockersAndDeniesSendEvenWhenLegacySwitchesAreOpen()
    {
        var organizationId = Guid.NewGuid();
        using var seededClient = await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);
        using var closedFactory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Phase12:Enabled", "false");
            builder.UseSetting("Phase12:Stage", "Closed");
            builder.UseSetting("Phase12:AllowSend", "false");
        });
        using var client = CopyAuthenticationHeaders(seededClient, closedFactory.CreateClient());

        var policy = await client.GetFromJsonAsync<ProductionPolicyResponse>("/api/production/policy");
        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            CreateRequest(Guid.NewGuid()));
        var preflight = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();

        Assert.NotNull(policy);
        Assert.False(policy.ReadyForSend);
        Assert.True(policy.CurrentUserRoleAllowed);
        Assert.False(policy.SendEnabled);
        Assert.NotEmpty(policy.Blockers);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(preflight);
        Assert.False(preflight.Authorized);
        Assert.Equal("PRODUCTION_ROLLOUT_CLOSED", preflight.ErrorCode);
    }

    [Fact]
    public async Task ProductionSendRequiresPrivilegedRole()
    {
        using var client = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true,
            roles: AppRoles.Operator);

        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            CreateRequest(Guid.NewGuid()));
        var preflight = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();
        var policy = await client.GetFromJsonAsync<ProductionPolicyResponse>("/api/production/policy");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(preflight);
        Assert.False(preflight.Authorized);
        Assert.Equal("PRODUCTION_ROLE_FORBIDDEN", preflight.ErrorCode);
        Assert.NotNull(policy);
        Assert.False(policy.CurrentUserRoleAllowed);
        Assert.False(policy.SendEnabled);
    }

    [Fact]
    public async Task CentralQuotaIsIdempotentAndBatchLimitFailsClosed()
    {
        var organizationId = Guid.NewGuid();
        var operationId = Guid.NewGuid();
        using var seededClient = await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);
        using var limitedFactory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Phase12:MaximumBatchSize", "2");
            builder.UseSetting("Phase12:MaximumDailySends", "1");
        });
        using var client = CopyAuthenticationHeaders(seededClient, limitedFactory.CreateClient());

        var first = await PreflightAsync(client, CreateRequest(operationId));
        var repeated = await PreflightAsync(client, CreateRequest(operationId));
        var conflict = await PreflightAsync(
            client,
            CreateRequest(operationId) with { DispatchFingerprint = new string('b', 64) });
        var quotaExceeded = await PreflightAsync(client, CreateRequest(Guid.NewGuid()));
        var batchExceeded = await PreflightAsync(client, CreateRequest(Guid.NewGuid()) with { BatchSize = 3 });
        var policy = await client.GetFromJsonAsync<ProductionPolicyResponse>("/api/production/policy");

        Assert.True(first.Authorized);
        Assert.True(repeated.Authorized);
        Assert.False(conflict.Authorized);
        Assert.Equal("PRODUCTION_IDEMPOTENCY_CONFLICT", conflict.ErrorCode);
        Assert.False(quotaExceeded.Authorized);
        Assert.Equal("PRODUCTION_DAILY_LIMIT_REACHED", quotaExceeded.ErrorCode);
        Assert.False(batchExceeded.Authorized);
        Assert.Equal("PRODUCTION_BATCH_LIMIT_EXCEEDED", batchExceeded.ErrorCode);
        Assert.NotNull(policy);
        Assert.Equal(1, policy.AuthorizedSendsToday);
        Assert.Equal(0, policy.RemainingSendsToday);
        Assert.False(policy.SendEnabled);
    }

    private static EmailSendPreflightRequest CreateRequest(Guid operationId) => new(
        operationId,
        DispatchWorkflowOptions.MicrosoftGraphProviderKey,
        new string('a', 64),
        1,
        DispatchWorkflowOptions.CurrentApplicationVersion,
        DispatchOperationMode.Send,
        1);

    private static async Task<EmailSendPreflightResponse> PreflightAsync(
        HttpClient client,
        EmailSendPreflightRequest request)
    {
        using var response = await client.PostAsJsonAsync("/api/email-dispatch/preflight", request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return Assert.IsType<EmailSendPreflightResponse>(
            await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>());
    }

    private static HttpClient CopyAuthenticationHeaders(HttpClient source, HttpClient target)
    {
        foreach (var header in source.DefaultRequestHeaders)
        {
            target.DefaultRequestHeaders.TryAddWithoutValidation(header.Key, header.Value);
        }

        return target;
    }
}
