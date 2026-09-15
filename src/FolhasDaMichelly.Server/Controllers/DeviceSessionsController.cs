using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/security/device-sessions")]
[Authorize(Policy = AuthorizationPolicies.UsersManage)]
public sealed class DeviceSessionsController(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    FolhasDbContext dbContext,
    IClock clock) : ControllerBase
{
    [HttpPost("{deviceSessionId:guid}/revoke")]
    [EnableRateLimiting("sensitive")]
    public async Task<IActionResult> Revoke(
        Guid deviceSessionId,
        CancellationToken cancellationToken)
    {
        var user = authenticatedUserAccessor.Current;
        var session = await dbContext.DeviceSessions.SingleOrDefaultAsync(
            item => item.Id == deviceSessionId &&
                item.OrganizationId == user.OrganizationId,
            cancellationToken);
        if (session is null)
        {
            return NotFound();
        }

        if (session.RevokedAtUtc is null)
        {
            session.RevokedAtUtc = clock.UtcNow;
            dbContext.AuditEvents.Add(
                new AuditEvent
                {
                    Id = Guid.NewGuid(),
                    OrganizationId = user.OrganizationId,
                    UserId = user.UserId,
                    DeviceId = user.DeviceSessionId,
                    EntityType = "device_session",
                    EntityId = session.Id.ToString("D"),
                    Action = "revoked",
                    Category = "authentication",
                    Severity = "warning",
                    RedactedDataJson = "{}",
                    TimestampUtc = clock.UtcNow,
                    CorrelationId = Guid.NewGuid(),
                });
            await dbContext.SaveChangesAsync(cancellationToken);
        }

        return NoContent();
    }
}
