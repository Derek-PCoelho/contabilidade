using System.Security.Claims;
using FolhasDaMichelly.Application.Abstractions;
using OpenIddict.Client;

namespace FolhasDaMichelly.Infrastructure.Identity;

public interface IDesktopAuthenticationService
{
    Task<ClaimsPrincipal> LoginAsync(
        Guid deviceId,
        string deviceName,
        CancellationToken cancellationToken);

    Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken);

    Task RefreshAsync(CancellationToken cancellationToken);

    Task LogoutAsync(CancellationToken cancellationToken);
}

public sealed class DesktopAuthenticationService(
    OpenIddictClientService clientService,
    ISecretStore secretStore) : IDesktopAuthenticationService
{
    private const string AccessTokenKey = "app-session/access-token";
    private const string RefreshTokenKey = "app-session/refresh-token";

    public async Task<ClaimsPrincipal> LoginAsync(
        Guid deviceId,
        string deviceName,
        CancellationToken cancellationToken)
    {
        var challenge = await clientService.ChallengeInteractivelyAsync(
            new OpenIddictClientModels.InteractiveChallengeRequest
            {
                AdditionalAuthorizationRequestParameters = new()
                {
                    ["device_id"] = deviceId.ToString("D"),
                    ["device_name"] = deviceName,
                },
                CancellationToken = cancellationToken,
            });
        var response = await clientService.AuthenticateInteractivelyAsync(
            new OpenIddictClientModels.InteractiveAuthenticationRequest
            {
                CancellationToken = cancellationToken,
                Nonce = challenge.Nonce,
            });
        await StoreTokensAsync(
            response.BackchannelAccessToken ?? response.FrontchannelAccessToken,
            response.RefreshToken,
            cancellationToken);
        return response.Principal;
    }

    public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken) =>
        secretStore.RetrieveAsync(AccessTokenKey, cancellationToken);

    public async Task RefreshAsync(CancellationToken cancellationToken)
    {
        var refreshToken = await secretStore.RetrieveAsync(RefreshTokenKey, cancellationToken);
        if (string.IsNullOrWhiteSpace(refreshToken))
        {
            throw new InvalidOperationException("No refresh token is available.");
        }

        var response = await clientService.AuthenticateWithRefreshTokenAsync(
            new OpenIddictClientModels.RefreshTokenAuthenticationRequest
            {
                CancellationToken = cancellationToken,
                RefreshToken = refreshToken,
            });
        await StoreTokensAsync(response.AccessToken, response.RefreshToken, cancellationToken);
    }

    public async Task LogoutAsync(CancellationToken cancellationToken)
    {
        await secretStore.RemoveAsync(AccessTokenKey, cancellationToken);
        await secretStore.RemoveAsync(RefreshTokenKey, cancellationToken);
    }

    private async Task StoreTokensAsync(
        string? accessToken,
        string? refreshToken,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(accessToken))
        {
            throw new InvalidOperationException("The authorization server returned no access token.");
        }

        await secretStore.StoreAsync(AccessTokenKey, accessToken, cancellationToken);
        if (!string.IsNullOrWhiteSpace(refreshToken))
        {
            await secretStore.StoreAsync(RefreshTokenKey, refreshToken, cancellationToken);
        }
    }
}
