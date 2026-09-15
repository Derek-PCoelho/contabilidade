using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace FolhasDaMichelly.Server.Hubs;

[Authorize(Policy = AuthorizationPolicies.ClientsRead)]
public sealed class SyncHub(IAuthenticatedUserAccessor authenticatedUserAccessor) : Hub
{
    public override async Task OnConnectedAsync()
    {
        var organizationId = authenticatedUserAccessor.Current.OrganizationId;
        await Groups.AddToGroupAsync(
            Context.ConnectionId,
            GroupName(organizationId),
            Context.ConnectionAborted);
        await base.OnConnectedAsync();
    }

    public static string GroupName(Guid organizationId) => $"organization:{organizationId:D}";
}

public sealed class SignalRSyncChangePublisher(IHubContext<SyncHub> hubContext)
    : ISyncChangePublisher
{
    public Task PublishAsync(
        Guid organizationId,
        SyncNotification notification,
        CancellationToken cancellationToken) =>
        hubContext.Clients.Group(SyncHub.GroupName(organizationId))
            .SendAsync("RecordsChanged", notification, cancellationToken);
}
