using System.Net.Http.Json;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Infrastructure.Documents;

public sealed class HttpClientResolver(HttpClient httpClient) : IClientResolver
{
    public async Task<ClientResolutionResult> ResolveAsync(
        ClientResolutionRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            using var response = await httpClient.PostAsJsonAsync(
                "api/document-recognition/resolve-client",
                request,
                cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return ClientResolutionResult.Unresolved(
                    response.StatusCode == System.Net.HttpStatusCode.Unauthorized
                        ? "client.resolution_authentication_required"
                        : "client.resolution_unavailable");
            }

            return await response.Content.ReadFromJsonAsync<ClientResolutionResult>(cancellationToken)
                ?? ClientResolutionResult.Unresolved("client.resolution_empty_response");
        }
        catch (HttpRequestException)
        {
            return ClientResolutionResult.Unresolved("client.resolution_offline");
        }
    }
}
