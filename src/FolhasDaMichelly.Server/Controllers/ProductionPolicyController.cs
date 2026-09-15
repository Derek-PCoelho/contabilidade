using System.Security.Claims;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Contracts.Production;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using FolhasDaMichelly.Server.Configuration;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using OpenIddict.Abstractions;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/production/policy")]
[Authorize(Policy = AuthorizationPolicies.EmailSend)]
[EnableRateLimiting("read")]
public sealed class ProductionPolicyController(
    IConfiguration configuration,
    FolhasDbContext dbContext,
    IClock clock) : ControllerBase
{
    [HttpGet]
    public async Task<ActionResult<ProductionPolicyResponse>> Get(CancellationToken cancellationToken)
    {
        var (options, snapshot) = ProductionRolloutConfiguration.Read(configuration);
        var organizationId = User.FindFirst(FolhasDaMichelly.Contracts.Identity.AppClaimNames.OrganizationId)?.Value;
        if (!Guid.TryParse(organizationId, out var parsedOrganizationId))
        {
            return Forbid();
        }

        var authorizationDateUtc = clock.UtcNow.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture);
        var authorizedToday = await dbContext.ProductionDispatchAuthorizations.CountAsync(
            item => item.OrganizationId == parsedOrganizationId &&
                item.AuthorizationDateUtc == authorizationDateUtc,
            cancellationToken);
        var userRoles = User.Claims
            .Where(claim => claim.Type is ClaimTypes.Role or OpenIddictConstants.Claims.Role)
            .Select(claim => claim.Value)
            .ToHashSet(StringComparer.Ordinal);
        var currentUserRoleAllowed = snapshot.AllowedRoles.Any(userRoles.Contains);

        return Ok(new ProductionPolicyResponse(
            options.Enabled,
            options.EnvironmentName,
            options.Stage.ToString(),
            snapshot.IsReadyForSend,
            currentUserRoleAllowed,
            snapshot.IsReadyForSend && currentUserRoleAllowed && authorizedToday < options.MaximumDailySends,
            options.MaximumBatchSize,
            options.MaximumDailySends,
            authorizedToday,
            Math.Max(0, options.MaximumDailySends - authorizedToday),
            options.MinimumApplicationVersion,
            snapshot.AllowedRoles,
            snapshot.Blockers));
    }
}
