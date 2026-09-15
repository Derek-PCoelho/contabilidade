using FolhasDaMichelly.Application.Identity;
using Microsoft.AspNetCore.Authorization;

namespace FolhasDaMichelly.Server.Authentication;

public sealed class ActiveDeviceSessionRequirement : IAuthorizationRequirement;

public sealed class ActiveDeviceSessionAuthorizationHandler(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    IDeviceSessionValidator deviceSessionValidator)
    : AuthorizationHandler<ActiveDeviceSessionRequirement>
{
    protected override async Task HandleRequirementAsync(
        AuthorizationHandlerContext context,
        ActiveDeviceSessionRequirement requirement)
    {
        ArgumentNullException.ThrowIfNull(context);
        ArgumentNullException.ThrowIfNull(requirement);

        AuthenticatedUser user;
        try
        {
            user = authenticatedUserAccessor.Current;
        }
        catch (InvalidOperationException)
        {
            return;
        }

        if (await deviceSessionValidator.IsActiveAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                CancellationToken.None))
        {
            context.Succeed(requirement);
        }
    }
}
