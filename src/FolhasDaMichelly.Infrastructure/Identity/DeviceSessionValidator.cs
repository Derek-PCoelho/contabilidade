using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Identity;

public sealed class DeviceSessionValidator(FolhasDbContext dbContext) : IDeviceSessionValidator
{
    public Task<bool> IsActiveAsync(
        Guid organizationId,
        Guid userId,
        Guid deviceSessionId,
        CancellationToken cancellationToken) =>
        dbContext.DeviceSessions.AnyAsync(
            session => session.Id == deviceSessionId &&
                session.OrganizationId == organizationId &&
                session.UserId == userId &&
                session.RevokedAtUtc == null,
            cancellationToken);
}
