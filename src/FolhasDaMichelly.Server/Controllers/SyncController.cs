using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/sync/clients")]
public sealed class SyncController(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    ISyncRepository syncRepository,
    ISyncChangePublisher changePublisher) : ControllerBase
{
    private const int MaximumCommandsPerPush = 100;

    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("read")]
    [HttpGet]
    public Task<PullSyncResponse> Pull(
        [FromQuery] long checkpoint = 0,
        CancellationToken cancellationToken = default)
    {
        var user = authenticatedUserAccessor.Current;
        return syncRepository.PullAsync(
            user.OrganizationId,
            Math.Max(0, checkpoint),
            cancellationToken);
    }

    [Authorize(Policy = AuthorizationPolicies.ClientsWrite)]
    [EnableRateLimiting("write")]
    [HttpPost]
    public async Task<ActionResult<PushSyncResponse>> Push(
        PushSyncRequest request,
        CancellationToken cancellationToken)
    {
        if (request.Commands.Count is 0 or > MaximumCommandsPerPush)
        {
            return BadRequest(
                new ProblemDetails
                {
                    Title = "Invalid sync batch",
                    Detail = $"A push must contain between 1 and {MaximumCommandsPerPush} commands.",
                    Status = StatusCodes.Status400BadRequest,
                });
        }

        var user = authenticatedUserAccessor.Current;
        var results = new List<SyncCommandResult>(request.Commands.Count);
        foreach (var command in request.Commands)
        {
            results.Add(
                await syncRepository.ApplyAsync(
                    user.OrganizationId,
                    user.UserId,
                    command,
                    cancellationToken));
        }

        if (results.Any(result => result.Status == SyncCommandStatus.Applied))
        {
            var checkpoint = await syncRepository.GetLatestCheckpointAsync(
                user.OrganizationId,
                cancellationToken);
            await changePublisher.PublishAsync(
                user.OrganizationId,
                new SyncNotification(checkpoint, "client"),
                cancellationToken);
        }

        return Ok(new PushSyncResponse(results));
    }
}
