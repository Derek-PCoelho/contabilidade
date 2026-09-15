using System.Net;
using System.Text;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Infrastructure.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase8GmailOAuthTests
{
    [Fact]
    public async Task DesktopOAuthUsesSystemLoopbackPkceComposeOnlyAndNativeSecretStoreContract()
    {
        var clock = new MutableClock();
        var secretStore = new InMemorySecretStore();
        var receiver = new RecordingAuthorizationReceiver();
        var handler = new RecordingHandler((request, index) => index switch
        {
            1 => Json(HttpStatusCode.OK, "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":3600,\"scope\":\"https://www.googleapis.com/auth/gmail.compose\"}"),
            2 => Json(HttpStatusCode.OK, "{\"emailAddress\":\"conta.teste@example.invalid\"}"),
            3 => Json(HttpStatusCode.OK, "{}"),
            _ => throw new InvalidOperationException($"Unexpected OAuth request {request.Method} {request.Path}"),
        });
        var options = CreateOptions();
        using var session = new GmailEmailAccountSession(
            new HttpClient(handler),
            options,
            secretStore,
            clock,
            receiver);

        var connected = await session.ConnectAsync(CancellationToken.None);

        Assert.True(connected.IsConnected);
        Assert.Equal("conta.teste@example.invalid", connected.AccountId);
        Assert.NotNull(receiver.AuthorizationUri);
        Assert.Contains("code_challenge_method=S256", receiver.AuthorizationUri.Query, StringComparison.Ordinal);
        Assert.Contains(Uri.EscapeDataString(GmailOptions.ComposeScope), receiver.AuthorizationUri.Query, StringComparison.Ordinal);
        Assert.DoesNotContain(Uri.EscapeDataString("https://mail.google.com/"), receiver.AuthorizationUri.Query, StringComparison.Ordinal);
        var exchange = handler.Requests[0];
        Assert.Contains("code_verifier=", exchange.Body, StringComparison.Ordinal);
        Assert.DoesNotContain("client_secret", exchange.Body, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("refresh-1", secretStore.StoredValue, StringComparison.Ordinal);

        await session.DisconnectAsync(CancellationToken.None);

        Assert.Null(secretStore.StoredValue);
        Assert.Contains("token=refresh-1", handler.Requests[2].Body, StringComparison.Ordinal);
    }

    [Fact]
    public async Task RevokedRefreshTokenIsRemovedAndMappedWithoutExposingToken()
    {
        var clock = new MutableClock();
        var secretStore = new InMemorySecretStore();
        var handler = new RecordingHandler((_, index) => index switch
        {
            1 => Json(HttpStatusCode.OK, "{\"access_token\":\"access-expiring\",\"refresh_token\":\"refresh-revoked\",\"expires_in\":1,\"scope\":\"https://www.googleapis.com/auth/gmail.compose\"}"),
            2 => Json(HttpStatusCode.OK, "{\"emailAddress\":\"conta.teste@example.invalid\"}"),
            3 => Json(HttpStatusCode.BadRequest, "{\"error\":\"invalid_grant\"}"),
            _ => throw new InvalidOperationException("Unexpected request."),
        });
        using var session = new GmailEmailAccountSession(
            new HttpClient(handler),
            CreateOptions(),
            secretStore,
            clock,
            new RecordingAuthorizationReceiver());
        Assert.True((await session.ConnectAsync(CancellationToken.None)).IsConnected);
        clock.UtcNow = clock.UtcNow.AddMinutes(5);

        var exception = await Assert.ThrowsAsync<EmailAccountSessionException>(() =>
            session.GetAccessTokenAsync(requireSendPermission: false, CancellationToken.None));

        Assert.Equal("AUTH_REVOKED", exception.Code);
        Assert.DoesNotContain("refresh-revoked", exception.Message, StringComparison.Ordinal);
        Assert.Null(secretStore.StoredValue);
    }

    [Fact]
    public async Task MismatchedOAuthStateIsRejectedBeforeTokenExchange()
    {
        var handler = new RecordingHandler((_, _) =>
            throw new InvalidOperationException("Token endpoint must not be called."));
        var secretStore = new InMemorySecretStore();
        using var session = new GmailEmailAccountSession(
            new HttpClient(handler),
            CreateOptions(),
            secretStore,
            new MutableClock(),
            new RecordingAuthorizationReceiver(returnMismatchedState: true));

        var status = await session.ConnectAsync(CancellationToken.None);

        Assert.False(status.IsConnected);
        Assert.Equal("AUTH_STATE_INVALID", status.ErrorCode);
        Assert.Empty(handler.Requests);
        Assert.Null(secretStore.StoredValue);
    }

    private static GmailOptions CreateOptions() => new()
    {
        Enabled = true,
        EmailSendEnabled = true,
        ClientId = "synthetic-client.apps.googleusercontent.com",
        ControlledRecipient = "controlado@example.invalid",
        AuthorizationEndpoint = new Uri("https://accounts.example.invalid/auth"),
        TokenEndpoint = new Uri("https://oauth.example.invalid/token"),
        RevocationEndpoint = new Uri("https://oauth.example.invalid/revoke"),
        ApiBaseAddress = new Uri("https://gmail.example.invalid/gmail/v1/"),
    };

    private static HttpResponseMessage Json(HttpStatusCode status, string json) => new(status)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };

    private sealed class RecordingAuthorizationReceiver(bool returnMismatchedState = false) :
        IGmailOAuthAuthorizationReceiver
    {
        public Uri? AuthorizationUri { get; private set; }

        public Task<GmailAuthorizationResponse> ReceiveAsync(
            Func<Uri, Uri> createAuthorizationUri,
            string expectedState,
            CancellationToken cancellationToken)
        {
            var redirect = new Uri("http://127.0.0.1:54321/oauth2/callback/");
            AuthorizationUri = createAuthorizationUri(redirect);
            var state = GetQueryValue(AuthorizationUri, "state");
            Assert.Equal(expectedState, state);
            return Task.FromResult(new GmailAuthorizationResponse(
                "synthetic-code",
                returnMismatchedState ? $"{state}-different" : state,
                null,
                redirect));
        }

        private static string GetQueryValue(Uri uri, string key) => uri.Query
            .TrimStart('?')
            .Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .Where(pair => Uri.UnescapeDataString(pair[0]) == key)
            .Select(pair => Uri.UnescapeDataString(pair[1]))
            .Single();
    }

    private sealed class RecordingHandler(
        Func<RequestSnapshot, int, HttpResponseMessage> responder) : HttpMessageHandler
    {
        public List<RequestSnapshot> Requests { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var snapshot = new RequestSnapshot(
                request.Method.Method,
                request.RequestUri!.AbsolutePath,
                request.Content is null ? string.Empty : await request.Content.ReadAsStringAsync(cancellationToken));
            Requests.Add(snapshot);
            return responder(snapshot, Requests.Count);
        }
    }

    private sealed record RequestSnapshot(string Method, string Path, string Body);

    private sealed class InMemorySecretStore : ISecretStore
    {
        public string? StoredValue { get; private set; }

        public Task StoreAsync(string key, string value, CancellationToken cancellationToken)
        {
            StoredValue = value;
            return Task.CompletedTask;
        }

        public Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken) =>
            Task.FromResult(StoredValue);

        public Task RemoveAsync(string key, CancellationToken cancellationToken)
        {
            StoredValue = null;
            return Task.CompletedTask;
        }
    }

    private sealed class MutableClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = new(2026, 8, 21, 12, 0, 0, TimeSpan.Zero);
    }
}
