using System.Data;
using System.Security.Claims;
using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using FolhasDaMichelly.Server.Configuration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using OpenIddict.Abstractions;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/email-dispatch")]
[Authorize(Policy = AuthorizationPolicies.EmailSend)]
public sealed class EmailDispatchController(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    FolhasDbContext dbContext,
    IClock clock,
    IConfiguration configuration) : ControllerBase
{
    [HttpPost("preflight")]
    [EnableRateLimiting("sensitive")]
    public async Task<ActionResult<EmailSendPreflightResponse>> Preflight(
        EmailSendPreflightRequest request,
        CancellationToken cancellationToken)
    {
        if (request.OperationId == Guid.Empty ||
            request.ProviderKey is not DispatchWorkflowOptions.MicrosoftGraphProviderKey and
                not DispatchWorkflowOptions.GmailProviderKey ||
            request.AttachmentCount is < 0 or > 1000 ||
            request.BatchSize is < 1 or > 1000 ||
            !Enum.IsDefined(request.OperationMode) ||
            string.IsNullOrWhiteSpace(request.DispatchFingerprint) ||
            request.DispatchFingerprint.Length != 64 ||
            request.DispatchFingerprint.Any(character => !Uri.IsHexDigit(character)) ||
            string.IsNullOrWhiteSpace(request.ApplicationVersion) ||
            request.ApplicationVersion.Length > 32 ||
            !Version.TryParse(request.ApplicationVersion, out var currentVersion))
        {
            return BadRequest();
        }

        var isGmail = request.ProviderKey == DispatchWorkflowOptions.GmailProviderKey;
        var phase = isGmail ? "Phase8" : "Phase7";
        var provider = isGmail ? "Gmail" : "MicrosoftGraph";
        var minimumVersion = configuration[$"{phase}:MinimumSendVersion"] ??
            DispatchWorkflowOptions.CurrentApplicationVersion;
        var globalMinimumVersion = configuration["Phase10:MinimumSupportedVersion"] ??
            DispatchWorkflowOptions.CurrentApplicationVersion;
        var providerEnabled = configuration.GetValue<bool>($"{phase}:{provider}:EmailSendEnabled");
        var globallyEnabled = configuration.GetValue<bool>("Phase10:EmailSendEnabled");
        var pilotEnabled = configuration.GetValue<bool>("Phase11:Enabled");
        var pilotOperationEnabled = request.OperationMode switch
        {
            DispatchOperationMode.Test => configuration.GetValue<bool>("Phase11:AllowTest"),
            DispatchOperationMode.Draft => configuration.GetValue<bool>("Phase11:AllowDraft"),
            DispatchOperationMode.Send => configuration.GetValue<bool>("Phase11:AllowSend"),
            _ => false,
        };
        var productionRequired = request.OperationMode == DispatchOperationMode.Send;
        var (productionOptions, productionSnapshot) = ProductionRolloutConfiguration.Read(configuration);
        var userRoles = User.Claims
            .Where(claim => claim.Type is ClaimTypes.Role or OpenIddictConstants.Claims.Role)
            .Select(claim => claim.Value)
            .ToHashSet(StringComparer.Ordinal);
        var roleAllowed = !productionRequired || productionSnapshot.AllowedRoles.Any(userRoles.Contains);
        var productionBatchAllowed = !productionRequired || request.BatchSize <= productionOptions.MaximumBatchSize;
        var productionReady = !productionRequired || productionSnapshot.IsReadyForSend;
        var remotelyEnabled = providerEnabled && globallyEnabled && (!pilotEnabled || pilotOperationEnabled) &&
            productionReady && roleAllowed && productionBatchAllowed;
        var providerVersionValid = Version.TryParse(minimumVersion, out var providerMinimum);
        var globalVersionValid = Version.TryParse(globalMinimumVersion, out var globalMinimum);
        var productionMinimum = new Version(0, 0);
        var productionVersionValid = !productionRequired ||
            Version.TryParse(productionOptions.MinimumApplicationVersion, out productionMinimum);
        var minimumCandidates = new List<(string Text, Version Version)>();
        if (providerVersionValid)
        {
            minimumCandidates.Add((minimumVersion, providerMinimum!));
        }

        if (globalVersionValid)
        {
            minimumCandidates.Add((globalMinimumVersion, globalMinimum!));
        }

        if (productionRequired && productionVersionValid)
        {
            minimumCandidates.Add((productionOptions.MinimumApplicationVersion, productionMinimum!));
        }

        var effectiveMinimum = minimumCandidates.Count == 0
            ? DispatchWorkflowOptions.CurrentApplicationVersion
            : minimumCandidates.MaxBy(candidate => candidate.Version).Text;
        var versionAllowed = providerVersionValid && globalVersionValid &&
            currentVersion >= providerMinimum &&
            currentVersion >= globalMinimum &&
            productionVersionValid &&
            (!productionRequired || currentVersion >= productionMinimum!);
        var pilotBlocked = pilotEnabled && !pilotOperationEnabled;
        var user = authenticatedUserAccessor.Current;
        var authorizationDateUtc = clock.UtcNow.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture);
        await using var transaction = await dbContext.Database.BeginTransactionAsync(
            IsolationLevel.Serializable,
            cancellationToken);
        var existingAuthorization = productionRequired
            ? await dbContext.ProductionDispatchAuthorizations.SingleOrDefaultAsync(
                item => item.OrganizationId == user.OrganizationId && item.OperationId == request.OperationId,
                cancellationToken)
            : null;
        var authorizedToday = productionRequired
            ? await dbContext.ProductionDispatchAuthorizations.CountAsync(
                item => item.OrganizationId == user.OrganizationId &&
                    item.AuthorizationDateUtc == authorizationDateUtc,
                cancellationToken)
            : 0;
        var dailyLimitAllowed = !productionRequired ||
            existingAuthorization is not null ||
            authorizedToday < productionOptions.MaximumDailySends;
        var idempotencyConsistent = !productionRequired ||
            existingAuthorization is null ||
            existingAuthorization.ProviderKey == request.ProviderKey &&
            string.Equals(
                existingAuthorization.DispatchFingerprint,
                request.DispatchFingerprint,
                StringComparison.OrdinalIgnoreCase) &&
            existingAuthorization.BatchSize == request.BatchSize &&
            existingAuthorization.AttachmentCount == request.AttachmentCount;
        var authorized = remotelyEnabled && versionAllowed && dailyLimitAllowed && idempotencyConsistent;
        if (authorized && productionRequired && existingAuthorization is null)
        {
            dbContext.ProductionDispatchAuthorizations.Add(new ProductionDispatchAuthorization
            {
                OperationId = request.OperationId,
                OrganizationId = user.OrganizationId,
                UserId = user.UserId,
                DeviceId = user.DeviceSessionId,
                ProviderKey = request.ProviderKey,
                DispatchFingerprint = request.DispatchFingerprint.ToLowerInvariant(),
                BatchSize = request.BatchSize,
                AttachmentCount = request.AttachmentCount,
                ApplicationVersion = currentVersion.ToString(),
                AuthorizationDateUtc = authorizationDateUtc,
                AuthorizedAtUtc = clock.UtcNow,
            });
        }

        var errorCode = authorized
            ? null
            : pilotBlocked
                ? request.OperationMode == DispatchOperationMode.Send
                    ? "PILOT_SEND_DISABLED"
                    : "PILOT_OPERATION_DISABLED"
                : !providerEnabled || !globallyEnabled
                    ? "SEND_DISABLED_REMOTELY"
                    : productionRequired && !productionReady
                        ? "PRODUCTION_ROLLOUT_CLOSED"
                        : productionRequired && !roleAllowed
                            ? "PRODUCTION_ROLE_FORBIDDEN"
                            : productionRequired && !productionBatchAllowed
                                ? "PRODUCTION_BATCH_LIMIT_EXCEEDED"
                                : productionRequired && !dailyLimitAllowed
                                    ? "PRODUCTION_DAILY_LIMIT_REACHED"
                                    : productionRequired && !idempotencyConsistent
                                        ? "PRODUCTION_IDEMPOTENCY_CONFLICT"
                                        : !versionAllowed
                                            ? "APP_VERSION_BELOW_MINIMUM"
                                            : "REMOTE_SEND_NOT_AUTHORIZED";
        var correlationId = Guid.NewGuid();
        dbContext.AuditEvents.Add(new AuditEvent
        {
            Id = Guid.NewGuid(),
            OrganizationId = user.OrganizationId,
            UserId = user.UserId,
            DeviceId = user.DeviceSessionId,
            EntityType = "email_dispatch_preflight",
            EntityId = request.OperationId.ToString("D"),
            Action = authorized ? "authorized" : "denied",
            Category = "email",
            Severity = authorized ? "information" : "warning",
            RedactedDataJson = JsonSerializer.Serialize(new
            {
                request.ProviderKey,
                request.OperationMode,
                request.AttachmentCount,
                request.BatchSize,
                ProductionStage = productionRequired ? productionOptions.Stage.ToString() : null,
                ApplicationVersion = currentVersion.ToString(),
                Authorized = authorized,
                ErrorCode = errorCode,
            }),
            TimestampUtc = clock.UtcNow,
            CorrelationId = correlationId,
        });
        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);

        return Ok(new EmailSendPreflightResponse(
            authorized,
            authorized,
            effectiveMinimum,
            correlationId,
            errorCode));
    }
}
