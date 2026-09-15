using Microsoft.AspNetCore.Identity;

namespace FolhasDaMichelly.Infrastructure.Identity;

public sealed class ApplicationUser : IdentityUser<Guid>
{
    public Guid OrganizationId { get; set; }

    public string DisplayName { get; set; } = string.Empty;

    public bool IsActive { get; set; } = true;

    public long Version { get; set; } = 1;

    public DateTimeOffset? LastAccessAtUtc { get; set; }
}

public sealed class ApplicationRole : IdentityRole<Guid>;

public sealed class DeviceSession
{
    public Guid Id { get; set; }

    public Guid OrganizationId { get; set; }

    public Guid UserId { get; set; }

    public string DeviceName { get; set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; set; }

    public DateTimeOffset LastSeenAtUtc { get; set; }

    public DateTimeOffset? RevokedAtUtc { get; set; }

    public bool IsRevoked => RevokedAtUtc is not null;
}
