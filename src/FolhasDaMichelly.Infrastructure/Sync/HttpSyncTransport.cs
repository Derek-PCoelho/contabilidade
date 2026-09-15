using System.Net.Http.Json;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;

namespace FolhasDaMichelly.Infrastructure.Sync;

public sealed class HttpSyncTransport(HttpClient httpClient) : ISyncTransport
{
    public async Task<PushSyncResponse> PushAsync(
        PushSyncRequest request,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.PostAsJsonAsync(
            "api/sync/clients",
            request,
            cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<PushSyncResponse>(cancellationToken)
            ?? throw new InvalidOperationException("The sync push response was empty.");
    }

    public async Task<PullSyncResponse> PullAsync(
        long checkpoint,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync(
            $"api/sync/clients?checkpoint={Math.Max(0, checkpoint)}",
            cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<PullSyncResponse>(cancellationToken)
            ?? throw new InvalidOperationException("The sync pull response was empty.");
    }
}
