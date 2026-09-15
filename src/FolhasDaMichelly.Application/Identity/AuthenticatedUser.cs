namespace FolhasDaMichelly.Application.Identity;

public sealed record AuthenticatedUser(
    Guid UserId,
    Guid OrganizationId,
    Guid DeviceSessionId,
    IReadOnlySet<string> Permissions,
    bool HasMultiFactorAuthentication)
{
    public bool HasPermission(string permission) => Permissions.Contains(permission);
}

public interface IAuthenticatedUserAccessor
{
    AuthenticatedUser Current { get; }
}

public interface IDeviceSessionValidator
{
    Task<bool> IsActiveAsync(
        Guid organizationId,
        Guid userId,
        Guid deviceSessionId,
        CancellationToken cancellationToken);
}
