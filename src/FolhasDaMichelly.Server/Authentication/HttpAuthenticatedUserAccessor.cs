using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Contracts.Identity;
using OpenIddict.Abstractions;

namespace FolhasDaMichelly.Server.Authentication;

public sealed class HttpAuthenticatedUserAccessor(IHttpContextAccessor httpContextAccessor)
    : IAuthenticatedUserAccessor
{
    public AuthenticatedUser Current
    {
        get
        {
            var user = httpContextAccessor.HttpContext?.User
                ?? throw new InvalidOperationException("No active HTTP user context is available.");
            if (!TryParse(user.GetClaim(OpenIddictConstants.Claims.Subject), out var userId) &&
                !TryParse(
                    user.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)?.Value,
                    out userId))
            {
                throw new InvalidOperationException("The authenticated user identifier is missing.");
            }

            if (!TryParse(user.FindFirst(AppClaimNames.OrganizationId)?.Value, out var organizationId) ||
                !TryParse(user.FindFirst(AppClaimNames.DeviceSessionId)?.Value, out var deviceSessionId))
            {
                throw new InvalidOperationException(
                    "The authenticated organization or device session is missing.");
            }

            var permissions = user.FindAll(AppClaimNames.Permission)
                .Select(claim => claim.Value)
                .ToHashSet(StringComparer.Ordinal);
            var hasMfa = user.FindAll(AppClaimNames.AuthenticationMethodReference)
                .Any(claim => string.Equals(claim.Value, "mfa", StringComparison.Ordinal));
            return new AuthenticatedUser(
                userId,
                organizationId,
                deviceSessionId,
                permissions,
                hasMfa);
        }
    }

    private static bool TryParse(string? value, out Guid result) =>
        Guid.TryParse(value, out result) && result != Guid.Empty;
}
