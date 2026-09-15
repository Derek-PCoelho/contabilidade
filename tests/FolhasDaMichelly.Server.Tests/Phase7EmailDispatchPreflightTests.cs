using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.AspNetCore.Hosting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase7EmailDispatchPreflightTests(Phase2WebApplicationFactory factory) :
    IClassFixture<Phase2WebApplicationFactory>
{
    [Fact]
    public async Task EmailSendPreflightRequiresMfaAndPersistsRedactedCentralAudit()
    {
        var organizationId = Guid.NewGuid();
        var operationId = Guid.NewGuid();
        var fingerprint = new string('a', 64);
        using var client = await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);
        var request = new EmailSendPreflightRequest(
            operationId,
            DispatchWorkflowOptions.MicrosoftGraphProviderKey,
            fingerprint,
            2,
            DispatchWorkflowOptions.CurrentApplicationVersion);

        using var response = await client.PostAsJsonAsync("/api/email-dispatch/preflight", request);
        var result = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(result);
        Assert.True(result.Authorized);
        Assert.True(result.EmailSendEnabled);
        await using var scope = factory.Services.CreateAsyncScope();
        var context = scope.ServiceProvider.GetRequiredService<FolhasDbContext>();
        var audit = await context.AuditEvents.SingleAsync(item =>
            item.OrganizationId == organizationId &&
            item.EntityId == operationId.ToString("D"));
        Assert.Equal("authorized", audit.Action);
        Assert.DoesNotContain(fingerprint, audit.RedactedDataJson, StringComparison.Ordinal);
        Assert.DoesNotContain("@", audit.RedactedDataJson, StringComparison.Ordinal);

        var malformedOperationId = Guid.NewGuid();
        using var malformed = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            request with
            {
                OperationId = malformedOperationId,
                ApplicationVersion = "0.7.0\nnot-a-version",
            });
        Assert.Equal(HttpStatusCode.BadRequest, malformed.StatusCode);
        Assert.False(await context.AuditEvents.AnyAsync(item =>
            item.EntityId == malformedOperationId.ToString("D")));
    }

    [Fact]
    public async Task EmailSendPreflightRejectsMissingMfaBeforeAuditMutation()
    {
        using var client = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: false);

        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            new EmailSendPreflightRequest(
                Guid.NewGuid(),
                DispatchWorkflowOptions.MicrosoftGraphProviderKey,
                new string('b', 64),
                1,
                DispatchWorkflowOptions.CurrentApplicationVersion));

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task Phase8GmailPreflightUsesItsOwnRemoteKillSwitch()
    {
        using var client = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);

        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            new EmailSendPreflightRequest(
                Guid.NewGuid(),
                DispatchWorkflowOptions.GmailProviderKey,
                new string('c', 64),
                1,
                DispatchWorkflowOptions.CurrentApplicationVersion));
        var result = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(result);
        Assert.True(result.Authorized);
        Assert.Equal("0.12.0", result.MinimumApplicationVersion);
    }

    [Fact]
    public async Task Phase10GlobalKillSwitchBlocksEveryRealProvider()
    {
        using var seededClient = await factory.CreateAuthenticatedClientAsync(
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            AppPermissions.EmailSend,
            hasMfa: true);
        using var disabledFactory = factory.WithWebHostBuilder(builder =>
            builder.UseSetting("Phase10:EmailSendEnabled", "false"));
        using var client = disabledFactory.CreateClient();
        foreach (var header in seededClient.DefaultRequestHeaders)
        {
            client.DefaultRequestHeaders.TryAddWithoutValidation(header.Key, header.Value);
        }

        using var response = await client.PostAsJsonAsync(
            "/api/email-dispatch/preflight",
            new EmailSendPreflightRequest(
                Guid.NewGuid(),
                DispatchWorkflowOptions.GmailProviderKey,
                new string('d', 64),
                1,
                DispatchWorkflowOptions.CurrentApplicationVersion));
        var result = await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(result);
        Assert.False(result.Authorized);
        Assert.False(result.EmailSendEnabled);
        Assert.Equal("SEND_DISABLED_REMOTELY", result.ErrorCode);
    }
}
