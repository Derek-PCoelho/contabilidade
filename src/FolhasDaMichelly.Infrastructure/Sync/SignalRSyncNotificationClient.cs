using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using Microsoft.AspNetCore.SignalR.Client;

namespace FolhasDaMichelly.Infrastructure.Sync;

public sealed class SignalRSyncNotificationClient : ISyncNotificationClient
{
    private readonly HubConnection connection;

    public SignalRSyncNotificationClient(Uri hubUri, Func<Task<string?>> accessTokenProvider)
    {
        ArgumentNullException.ThrowIfNull(hubUri);
        ArgumentNullException.ThrowIfNull(accessTokenProvider);

        connection = new HubConnectionBuilder()
            .WithUrl(
                hubUri,
                options => options.AccessTokenProvider = accessTokenProvider)
            .WithAutomaticReconnect()
            .Build();
        connection.On<SyncNotification>("RecordsChanged", OnChangeDetectedAsync);
    }

    public event Func<SyncNotification, Task>? ChangeDetected;

    public Task StartAsync(CancellationToken cancellationToken) =>
        connection.StartAsync(cancellationToken);

    public Task StopAsync(CancellationToken cancellationToken) =>
        connection.StopAsync(cancellationToken);

    public ValueTask DisposeAsync() => connection.DisposeAsync();

    private async Task OnChangeDetectedAsync(SyncNotification notification)
    {
        var handlers = ChangeDetected;
        if (handlers is not null)
        {
            await handlers(notification);
        }
    }
}
