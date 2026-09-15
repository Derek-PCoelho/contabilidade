using System.Net.Http.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class HttpRemoteEmailSendGuard(HttpClient httpClient) : IRemoteEmailSendGuard
{
    public async Task<EmailSendPreflightResponse> AuthorizeAsync(
        EmailSendPreflightRequest request,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.PostAsJsonAsync(
            "api/email-dispatch/preflight",
            request,
            cancellationToken);
        if (response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)
        {
            return new EmailSendPreflightResponse(
                false,
                false,
                DispatchWorkflowOptions.CurrentApplicationVersion,
                Guid.NewGuid(),
                "REMOTE_SEND_FORBIDDEN");
        }

        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<EmailSendPreflightResponse>(cancellationToken)
            ?? throw new HttpRequestException("O preflight central retornou resposta vazia.");
    }
}
