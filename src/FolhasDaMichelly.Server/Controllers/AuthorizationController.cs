using System.Security.Claims;
using FolhasDaMichelly.Contracts.Identity;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.AspNetCore;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using OpenIddict.Abstractions;
using OpenIddict.Server.AspNetCore;
using static OpenIddict.Abstractions.OpenIddictConstants;

namespace FolhasDaMichelly.Server.Controllers;

[EnableRateLimiting("authentication")]
public sealed class AuthorizationController(
    UserManager<ApplicationUser> userManager,
    SignInManager<ApplicationUser> signInManager,
    FolhasDbContext dbContext) : Controller
{
    [Authorize(AuthenticationSchemes = "Identity.Application")]
    [HttpGet("~/connect/authorize")]
    public async Task<IActionResult> Authorize(CancellationToken cancellationToken)
    {
        var request = HttpContext.GetOpenIddictServerRequest()
            ?? throw new InvalidOperationException("The OpenID Connect request is unavailable.");
        var user = await userManager.GetUserAsync(User)
            ?? throw new InvalidOperationException("The authenticated user no longer exists.");
        if (!user.IsActive || !await signInManager.CanSignInAsync(user))
        {
            return Forbid(OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
        }

        var deviceSession = await ResolveDeviceSessionAsync(
            user,
            request.GetParameter("device_id")?.ToString(),
            request.GetParameter("device_name")?.ToString(),
            cancellationToken);
        var principal = await CreatePrincipalAsync(
            user,
            deviceSession.Id,
            request.GetScopes(),
            User);
        return SignIn(principal, OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
    }

    [HttpPost("~/connect/token")]
    [IgnoreAntiforgeryToken]
    [Produces("application/json")]
    public async Task<IActionResult> Exchange(CancellationToken cancellationToken)
    {
        var request = HttpContext.GetOpenIddictServerRequest()
            ?? throw new InvalidOperationException("The OpenID Connect request is unavailable.");
        if (!request.IsAuthorizationCodeGrantType() && !request.IsRefreshTokenGrantType())
        {
            throw new InvalidOperationException("Only authorization code and refresh grants are supported.");
        }

        var result = await HttpContext.AuthenticateAsync(
            OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
        var subject = result.Principal?.GetClaim(Claims.Subject);
        var user = subject is null ? null : await userManager.FindByIdAsync(subject);
        if (user is null || !user.IsActive || !await signInManager.CanSignInAsync(user))
        {
            return InvalidGrant("The user session has been revoked.");
        }

        var sessionClaim = result.Principal?.FindFirst(AppClaimNames.DeviceSessionId)?.Value;
        if (!Guid.TryParse(sessionClaim, out var sessionId) ||
            !await dbContext.DeviceSessions.AnyAsync(
                session => session.Id == sessionId &&
                    session.UserId == user.Id &&
                    session.OrganizationId == user.OrganizationId &&
                    session.RevokedAtUtc == null,
                cancellationToken))
        {
            return InvalidGrant("The device session has been revoked.");
        }

        var principal = await CreatePrincipalAsync(
            user,
            sessionId,
            result.Principal?.GetScopes() ?? [],
            result.Principal);
        return SignIn(principal, OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
    }

    [Authorize(AuthenticationSchemes = "Identity.Application")]
    [HttpPost("~/connect/logout")]
    [ValidateAntiForgeryToken]
    public async Task<IActionResult> Logout()
    {
        await signInManager.SignOutAsync();
        return SignOut(
            new AuthenticationProperties { RedirectUri = "/" },
            OpenIddictServerAspNetCoreDefaults.AuthenticationScheme);
    }

    private async Task<ClaimsPrincipal> CreatePrincipalAsync(
        ApplicationUser user,
        Guid deviceSessionId,
        IEnumerable<string> requestedScopes,
        ClaimsPrincipal? sourcePrincipal)
    {
        var identity = new ClaimsIdentity(
            TokenValidationParameters.DefaultAuthenticationType,
            Claims.Name,
            Claims.Role);
        identity.SetClaim(Claims.Subject, user.Id.ToString("D"));
        identity.SetClaim(Claims.Email, user.Email);
        identity.SetClaim(Claims.Name, user.DisplayName);
        identity.SetClaim(Claims.PreferredUsername, user.UserName);
        identity.AddClaim(new Claim(AppClaimNames.OrganizationId, user.OrganizationId.ToString("D")));
        identity.AddClaim(new Claim(AppClaimNames.DeviceSessionId, deviceSessionId.ToString("D")));

        var roles = await userManager.GetRolesAsync(user);
        foreach (var role in roles)
        {
            identity.AddClaim(new Claim(Claims.Role, role));
        }
        foreach (var permission in roles
                     .SelectMany(RolePermissionCatalog.ForRole)
                     .Distinct(StringComparer.Ordinal))
        {
            identity.AddClaim(new Claim(AppClaimNames.Permission, permission));
        }

        foreach (var method in sourcePrincipal?.FindAll(
                     AppClaimNames.AuthenticationMethodReference) ?? [])
        {
            identity.AddClaim(new Claim(method.Type, method.Value));
        }

        var scopes = requestedScopes.Intersect(
            [Scopes.OpenId, Scopes.OfflineAccess, Scopes.Email, Scopes.Profile, Scopes.Roles, "folhas_api"],
            StringComparer.Ordinal);
        identity.SetScopes(scopes);
        identity.SetResources("folhas_api");
        identity.SetDestinations(GetDestinations);
        return new ClaimsPrincipal(identity);
    }

    private async Task<DeviceSession> ResolveDeviceSessionAsync(
        ApplicationUser user,
        string? deviceId,
        string? deviceName,
        CancellationToken cancellationToken)
    {
        var id = Guid.TryParse(deviceId, out var parsed) && parsed != Guid.Empty
            ? parsed
            : Guid.NewGuid();
        var session = await dbContext.DeviceSessions.SingleOrDefaultAsync(
            item => item.Id == id && item.UserId == user.Id &&
                item.OrganizationId == user.OrganizationId,
            cancellationToken);
        if (session?.IsRevoked == true)
        {
            throw new InvalidOperationException("The requested device session is revoked.");
        }

        if (session is null)
        {
            session = new DeviceSession
            {
                Id = id,
                OrganizationId = user.OrganizationId,
                UserId = user.Id,
                DeviceName = string.IsNullOrWhiteSpace(deviceName)
                    ? "Desktop"
                    : deviceName.Trim()[..Math.Min(deviceName.Trim().Length, 160)],
                CreatedAtUtc = DateTimeOffset.UtcNow,
                LastSeenAtUtc = DateTimeOffset.UtcNow,
            };
            dbContext.DeviceSessions.Add(session);
        }
        else
        {
            session.LastSeenAtUtc = DateTimeOffset.UtcNow;
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        return session;
    }

    private ForbidResult InvalidGrant(string description) =>
        Forbid(
            authenticationSchemes: OpenIddictServerAspNetCoreDefaults.AuthenticationScheme,
            properties: new AuthenticationProperties(
                new Dictionary<string, string?>(StringComparer.Ordinal)
                {
                    [OpenIddictServerAspNetCoreConstants.Properties.Error] = Errors.InvalidGrant,
                    [OpenIddictServerAspNetCoreConstants.Properties.ErrorDescription] = description,
                }));

    private static IEnumerable<string> GetDestinations(Claim claim)
    {
        if (claim.Type == "AspNet.Identity.SecurityStamp")
        {
            yield break;
        }

        yield return Destinations.AccessToken;
        if (claim.Type is Claims.Name or Claims.Email or Claims.PreferredUsername or Claims.Role)
        {
            yield return Destinations.IdentityToken;
        }
    }
}
