using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class GmailEmailAccountSession(
    HttpClient httpClient,
    GmailOptions options,
    ISecretStore secretStore,
    IClock clock,
    IGmailOAuthAuthorizationReceiver authorizationReceiver) : IEmailAccountSession, IDisposable
{
    internal const string TokenCacheKey = "email-provider/google-gmail/oauth-v1-token";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private readonly SemaphoreSlim tokenGate = new(1, 1);

    public string ProviderKey => DispatchWorkflowOptions.GmailProviderKey;

    public async Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken)
    {
        if (!options.IsConfigured)
        {
            return NotConfigured();
        }

        var token = await ReadTokenAsync(cancellationToken);
        return token is null
            ? Disconnected()
            : Connected(token.AccountEmail);
    }

    public async Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken)
    {
        if (!options.IsConfigured)
        {
            return NotConfigured();
        }

        var state = CreateRandomBase64Url(32);
        var verifier = CreateRandomBase64Url(64);
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        try
        {
            var authorization = await authorizationReceiver.ReceiveAsync(
                redirectUri => CreateAuthorizationUri(redirectUri, state, challenge),
                state,
                cancellationToken);
            if (!string.IsNullOrWhiteSpace(authorization.Error))
            {
                return authorization.Error == "access_denied"
                    ? Cancelled()
                    : Failed("AUTH_REQUIRED");
            }

            if (!string.Equals(authorization.State, state, StringComparison.Ordinal) ||
                string.IsNullOrWhiteSpace(authorization.Code) ||
                !authorization.RedirectUri.IsLoopback ||
                authorization.RedirectUri.Scheme != Uri.UriSchemeHttp)
            {
                return Failed("AUTH_STATE_INVALID");
            }

            var token = await ExchangeAuthorizationCodeAsync(
                authorization.Code,
                authorization.RedirectUri,
                verifier,
                cancellationToken);
            var accountEmail = await GetProfileEmailAsync(token.AccessToken, cancellationToken);
            token = token with { AccountEmail = accountEmail };
            await WriteTokenAsync(token, cancellationToken);
            return Connected(accountEmail);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            return Cancelled();
        }
        catch (Exception exception) when (
            exception is HttpRequestException or JsonException or EmailAccountSessionException)
        {
            return Failed(exception is EmailAccountSessionException session ? session.Code : "AUTH_REQUIRED");
        }
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken)
    {
        var token = await ReadTokenAsync(cancellationToken);
        try
        {
            if (token is not null)
            {
                using var content = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["token"] = string.IsNullOrWhiteSpace(token.RefreshToken)
                        ? token.AccessToken
                        : token.RefreshToken,
                });
                using var response = await httpClient.PostAsync(options.RevocationEndpoint, content, cancellationToken);
                _ = response.StatusCode;
            }
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException)
        {
            // A sessão local deve ser encerrada mesmo se o provedor estiver indisponível.
        }
        finally
        {
            await secretStore.RemoveAsync(TokenCacheKey, CancellationToken.None);
        }
    }

    public async Task<string> GetAccessTokenAsync(
        bool requireSendPermission,
        CancellationToken cancellationToken)
    {
        if (!options.IsConfigured)
        {
            throw new EmailAccountSessionException(
                "GMAIL_NOT_CONFIGURED",
                "Configure o cliente OAuth Google e o destinatário controlado da Fase 8.");
        }

        if (requireSendPermission && !options.EmailSendEnabled)
        {
            throw new EmailAccountSessionException(
                "EMAIL_SEND_DISABLED",
                "O envio pelo Gmail está desligado nesta instalação.");
        }

        await tokenGate.WaitAsync(cancellationToken);
        try
        {
            var token = await ReadTokenAsync(cancellationToken)
                ?? throw new EmailAccountSessionException(
                    "AUTH_REQUIRED",
                    "Conecte uma conta Google dedicada antes de continuar.");
            if (token.ExpiresAtUtc > clock.UtcNow.AddMinutes(2))
            {
                return token.AccessToken;
            }

            return await RefreshAccessTokenAsync(token, cancellationToken);
        }
        finally
        {
            tokenGate.Release();
        }
    }

    private Uri CreateAuthorizationUri(Uri redirectUri, string state, string challenge)
    {
        var parameters = new Dictionary<string, string>
        {
            ["client_id"] = options.ClientId.Trim(),
            ["redirect_uri"] = redirectUri.AbsoluteUri,
            ["response_type"] = "code",
            ["scope"] = string.Join(' ', options.Scopes),
            ["state"] = state,
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
            ["access_type"] = "offline",
            ["prompt"] = "consent",
        };
        return new UriBuilder(options.AuthorizationEndpoint)
        {
            Query = string.Join('&', parameters.Select(pair =>
                $"{Uri.EscapeDataString(pair.Key)}={Uri.EscapeDataString(pair.Value)}")),
        }.Uri;
    }

    private async Task<GmailStoredToken> ExchangeAuthorizationCodeAsync(
        string code,
        Uri redirectUri,
        string verifier,
        CancellationToken cancellationToken)
    {
        using var content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = options.ClientId.Trim(),
            ["code"] = code,
            ["code_verifier"] = verifier,
            ["grant_type"] = "authorization_code",
            ["redirect_uri"] = redirectUri.AbsoluteUri,
        });
        using var response = await httpClient.PostAsync(options.TokenEndpoint, content, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "O Google não autorizou esta conexão.");
        }

        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        var root = document.RootElement;
        var accessToken = RequiredString(root, "access_token");
        var refreshToken = RequiredString(root, "refresh_token");
        var expiresIn = root.GetProperty("expires_in").GetInt32();
        var scopes = root.TryGetProperty("scope", out var scopeElement)
            ? scopeElement.GetString() ?? string.Empty
            : string.Join(' ', options.Scopes);
        if (!scopes.Split(' ', StringSplitOptions.RemoveEmptyEntries).Contains(GmailOptions.ComposeScope, StringComparer.Ordinal))
        {
            throw new EmailAccountSessionException("AUTH_SCOPE_MISSING", "O consentimento não incluiu a permissão Gmail necessária.");
        }

        return new GmailStoredToken(
            accessToken,
            refreshToken,
            clock.UtcNow.AddSeconds(Math.Max(1, expiresIn)),
            scopes,
            string.Empty);
    }

    private async Task<string> RefreshAccessTokenAsync(
        GmailStoredToken token,
        CancellationToken cancellationToken)
    {
        using var content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = options.ClientId.Trim(),
            ["refresh_token"] = token.RefreshToken,
            ["grant_type"] = "refresh_token",
        });
        using var response = await httpClient.PostAsync(options.TokenEndpoint, content, cancellationToken);
        if (response.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.Unauthorized)
        {
            await secretStore.RemoveAsync(TokenCacheKey, cancellationToken);
            throw new EmailAccountSessionException(
                "AUTH_REVOKED",
                "A autorização Google expirou ou foi revogada; conecte novamente.");
        }

        if (!response.IsSuccessStatusCode)
        {
            throw new EmailAccountSessionException(
                IsTransient(response.StatusCode) ? "AUTH_TEMPORARILY_UNAVAILABLE" : "AUTH_REQUIRED",
                "Não foi possível renovar a autorização Google.");
        }

        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        var updated = token with
        {
            AccessToken = RequiredString(document.RootElement, "access_token"),
            ExpiresAtUtc = clock.UtcNow.AddSeconds(
                Math.Max(1, document.RootElement.GetProperty("expires_in").GetInt32())),
        };
        await WriteTokenAsync(updated, cancellationToken);
        return updated.AccessToken;
    }

    private async Task<string> GetProfileEmailAsync(
        string accessToken,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            new Uri(options.ApiBaseAddress, "users/me/profile"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "Não foi possível confirmar a conta Gmail conectada.");
        }

        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        return RequiredString(document.RootElement, "emailAddress");
    }

    private async Task<GmailStoredToken?> ReadTokenAsync(CancellationToken cancellationToken)
    {
        var serialized = await secretStore.RetrieveAsync(TokenCacheKey, cancellationToken);
        if (string.IsNullOrWhiteSpace(serialized))
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<GmailStoredToken>(serialized, SerializerOptions);
        }
        catch (JsonException)
        {
            await secretStore.RemoveAsync(TokenCacheKey, cancellationToken);
            return null;
        }
    }

    private Task WriteTokenAsync(GmailStoredToken token, CancellationToken cancellationToken) =>
        secretStore.StoreAsync(
            TokenCacheKey,
            JsonSerializer.Serialize(token, SerializerOptions),
            cancellationToken);

    private static string RequiredString(JsonElement root, string property)
    {
        var value = root.GetProperty(property).GetString();
        return string.IsNullOrWhiteSpace(value) ? throw new JsonException($"Missing {property}.") : value;
    }

    private static string CreateRandomBase64Url(int bytes)
    {
        var buffer = RandomNumberGenerator.GetBytes(bytes);
        try
        {
            return Base64Url(buffer);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(buffer);
        }
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static bool IsTransient(HttpStatusCode statusCode) =>
        statusCode is HttpStatusCode.TooManyRequests or
            HttpStatusCode.RequestTimeout or
            HttpStatusCode.InternalServerError or
            HttpStatusCode.BadGateway or
            HttpStatusCode.ServiceUnavailable or
            HttpStatusCode.GatewayTimeout;

    private EmailAccountConnectionStatus NotConfigured() => new(
        ProviderKey,
        false,
        false,
        null,
        "Google não configurado",
        "GMAIL_NOT_CONFIGURED");

    private EmailAccountConnectionStatus Disconnected() => new(
        ProviderKey,
        true,
        false,
        null,
        "Google desconectado",
        "AUTH_REQUIRED");

    private EmailAccountConnectionStatus Connected(string accountEmail) => new(
        ProviderKey,
        true,
        true,
        accountEmail,
        accountEmail,
        null);

    private EmailAccountConnectionStatus Cancelled() => new(
        ProviderKey,
        true,
        false,
        null,
        "Conexão Google cancelada",
        "AUTH_CANCELLED");

    private EmailAccountConnectionStatus Failed(string code) => new(
        ProviderKey,
        true,
        false,
        null,
        "Não foi possível conectar a conta Google",
        code);

    private sealed record GmailStoredToken(
        string AccessToken,
        string RefreshToken,
        DateTimeOffset ExpiresAtUtc,
        string Scope,
        string AccountEmail);

    public void Dispose()
    {
        tokenGate.Dispose();
        GC.SuppressFinalize(this);
    }
}
