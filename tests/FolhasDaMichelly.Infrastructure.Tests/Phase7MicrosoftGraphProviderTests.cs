using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase7MicrosoftGraphProviderTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        "folhas-phase7-graph",
        Guid.NewGuid().ToString("N"));

    public Phase7MicrosoftGraphProviderTests() => Directory.CreateDirectory(temporaryDirectory);

    [Fact]
    public void GraphConfigurationUsesLoopbackAndDoesNotRequestMailSendForDraftOnly()
    {
        var options = CreateOptions();

        Assert.True(options.IsConfigured);
        Assert.Equal(["Mail.ReadWrite"], options.DraftScopes);
        Assert.DoesNotContain("Mail.Send", options.DraftScopes);
        Assert.Contains("Mail.Send", options.SendScopes);
        options.RedirectUri = "https://example.com/callback";
        Assert.False(options.IsConfigured);
    }

    [Fact]
    public async Task DraftFirstSendUsesControlledRecipientAttachmentAndMaps202ToAcceptedOnly()
    {
        var attachment = await CreateAttachmentAsync("folha-sintetica.pdf", 2048);
        var attachmentPages = 0;
        var handler = new RecordingHandler((request, index) => (request.Method, request.Path) switch
        {
            ("GET", "/v1.0/me/messages") => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("POST", "/v1.0/me/messages") => Json(HttpStatusCode.Created, "{\"id\":\"immutable-draft-1\"}"),
            ("GET", "/v1.0/me/messages/immutable-draft-1/attachments") when attachmentPages++ == 0 => Json(
                HttpStatusCode.OK,
                "{\"value\":[{\"contentId\":\"folhas-existing\"}],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/messages/immutable-draft-1/attachments?$skip=1\"}"),
            ("GET", "/v1.0/me/messages/immutable-draft-1/attachments") => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("POST", "/v1.0/me/messages/immutable-draft-1/attachments") => Json(HttpStatusCode.Created, "{\"id\":\"attachment-1\"}"),
            ("POST", "/v1.0/me/messages/immutable-draft-1/send") => new HttpResponseMessage(HttpStatusCode.Accepted),
            _ => throw new InvalidOperationException($"Unexpected Graph request #{index}: {request.Method} {request.Path}"),
        });
        var provider = CreateProvider(handler);

        var result = await provider.SendAsync(CreateEnvelope(attachment), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.AcceptedByProvider, result.State);
        Assert.Equal("immutable-draft-1", result.ProviderMessageId);
        Assert.Equal("immutable-draft-1", result.ProviderDraftId);
        Assert.Equal(6, handler.Requests.Count);
        Assert.Equal(2, attachmentPages);
        var create = handler.Requests.Single(request => request.Method == "POST" && request.Path == "/v1.0/me/messages");
        Assert.Contains("controlado@example.invalid", create.Body, StringComparison.Ordinal);
        Assert.DoesNotContain("cliente-real@example.com", create.Body, StringComparison.Ordinal);
        Assert.Contains("singleValueExtendedProperties", create.Body, StringComparison.Ordinal);
        Assert.Contains("x-folhas-idempotency", create.Body, StringComparison.Ordinal);
        var lookup = handler.Requests.First(request => request.Method == "GET" && request.Path == "/v1.0/me/messages");
        Assert.Contains("singleValueExtendedProperties%2FAny", lookup.Query, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("FolhasIdempotency", lookup.Query, StringComparison.Ordinal);
        Assert.All(handler.Requests.Where(request => request.Path.StartsWith("/v1.0/", StringComparison.Ordinal)), request =>
        {
            Assert.Equal("test-access-token", request.BearerToken);
            Assert.Equal("IdType=\"ImmutableId\"", request.Prefer);
        });
    }

    [Fact]
    public async Task AmbiguousSendIsNeverRetriedAndReconciliationFindsImmutableSentCopy()
    {
        var attachment = await CreateAttachmentAsync("guia-sintetica.pdf", 1024);
        var sendCalls = 0;
        var findCalls = 0;
        var handler = new RecordingHandler((request, _) => (request.Method, request.Path) switch
        {
            ("GET", "/v1.0/me/messages") when findCalls++ == 0 => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("GET", "/v1.0/me/messages") => Json(HttpStatusCode.OK, "{\"value\":[{\"id\":\"immutable-draft-2\",\"isDraft\":false}]}"),
            ("POST", "/v1.0/me/messages") => Json(HttpStatusCode.Created, "{\"id\":\"immutable-draft-2\"}"),
            ("GET", "/v1.0/me/messages/immutable-draft-2/attachments") => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("POST", "/v1.0/me/messages/immutable-draft-2/attachments") => Json(HttpStatusCode.Created, "{\"id\":\"attachment-2\"}"),
            ("POST", "/v1.0/me/messages/immutable-draft-2/send") => ThrowNetwork(ref sendCalls),
            _ => throw new InvalidOperationException($"Unexpected Graph request: {request.Method} {request.Path}"),
        });
        var provider = CreateProvider(handler);
        var envelope = CreateEnvelope(attachment);

        var result = await provider.SendAsync(envelope, CancellationToken.None);
        var reconciled = await provider.ReconcileAsync(CreateAttempt(envelope), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.Ambiguous, result.State);
        Assert.Equal("SEND_AMBIGUOUS", result.ErrorCode);
        Assert.Equal(1, sendCalls);
        Assert.Equal(DeliveryAttemptState.AcceptedByProvider, reconciled.State);
        Assert.Equal("immutable-draft-2", reconciled.ProviderMessageId);
    }

    [Fact]
    public async Task ReconciliationOfExistingDraftAllowsExplicitHumanConfirmedResume()
    {
        var handler = new RecordingHandler((request, _) => Json(
            HttpStatusCode.OK,
            "{\"value\":[{\"id\":\"immutable-draft-3\",\"isDraft\":true}]}"));
        var provider = CreateProvider(handler);
        var envelope = CreateEnvelope();

        var result = await provider.ReconcileAsync(CreateAttempt(envelope), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.DraftCreated, result.State);
        Assert.Equal("immutable-draft-3", result.ProviderDraftId);
        Assert.Equal("GRAPH_DRAFT_CONFIRMED_NOT_SENT", result.ErrorCode);
    }

    [Fact]
    public async Task RetrySafeReadHonorsRetryAfterButControlledRecipientFailsBeforeNetwork()
    {
        var calls = 0;
        var handler = new RecordingHandler((_, _) => calls++ == 0
            ? RetryAfterResponse()
            : Json(HttpStatusCode.OK, "{\"value\":[]}"));
        var provider = CreateProvider(handler);
        var result = await provider.ReconcileAsync(CreateAttempt(CreateEnvelope()), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.Ambiguous, result.State);
        Assert.Equal(2, calls);

        var unsafeEnvelope = CreateEnvelope() with { To = ["cliente-real@example.com"] };
        var rejected = await provider.SendAsync(unsafeEnvelope, CancellationToken.None);
        Assert.Equal(DeliveryAttemptState.FailedPermanent, rejected.State);
        Assert.Equal("GRAPH_RECIPIENT_NOT_CONTROLLED", rejected.ErrorCode);
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task LargeAttachmentUsesPreAuthorizedUploadSessionAndChunkRange()
    {
        var attachment = await CreateAttachmentAsync("lote-sintetico.pdf", 3 * 1024 * 1024);
        var handler = new RecordingHandler((request, _) => (request.Method, request.Path) switch
        {
            ("GET", "/v1.0/me/messages") => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("POST", "/v1.0/me/messages") => Json(HttpStatusCode.Created, "{\"id\":\"immutable-large\"}"),
            ("GET", "/v1.0/me/messages/immutable-large/attachments") => Json(HttpStatusCode.OK, "{\"value\":[]}"),
            ("POST", "/v1.0/me/messages/immutable-large/attachments/createUploadSession") =>
                Json(HttpStatusCode.Created, "{\"uploadUrl\":\"https://upload.example.invalid/session-1\"}"),
            ("PUT", "/session-1") => Json(HttpStatusCode.Created, "{\"id\":\"large-attachment\"}"),
            _ => throw new InvalidOperationException($"Unexpected Graph request: {request.Method} {request.Path}"),
        });
        var provider = CreateProvider(handler);

        var result = await provider.CreateDraftAsync(CreateEnvelope(attachment), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.DraftCreated, result.State);
        var upload = Assert.Single(handler.Requests, request => request.Method == "PUT");
        var session = Assert.Single(handler.Requests, request =>
            request.Method == "POST" && request.Path.EndsWith("createUploadSession", StringComparison.Ordinal));
        Assert.Contains("AttachmentItem", session.Body, StringComparison.Ordinal);
        Assert.Equal("bytes 0-3145727/3145728", upload.ContentRange);
        Assert.Null(upload.BearerToken);
    }

    [Fact]
    public async Task RevokedOAuthMapsToPermanentAuthFailureWithoutHttpCall()
    {
        var handler = new RecordingHandler((_, _) => throw new InvalidOperationException("HTTP must not be called."));
        var options = CreateOptions();
        var provider = new MicrosoftGraphEmailProvider(
            new HttpClient(handler) { BaseAddress = options.ApiBaseAddress },
            new FixedAccountSession(new EmailAccountSessionException("AUTH_REVOKED", "Revogada.")),
            options);

        var result = await provider.CreateDraftAsync(CreateEnvelope(), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.FailedPermanent, result.State);
        Assert.Equal("AUTH_REVOKED", result.ErrorCode);
        Assert.Empty(handler.Requests);
    }

    public void Dispose()
    {
        Directory.Delete(temporaryDirectory, true);
        GC.SuppressFinalize(this);
    }

    private static MicrosoftGraphEmailProvider CreateProvider(RecordingHandler handler)
    {
        var options = CreateOptions();
        return new MicrosoftGraphEmailProvider(
            new HttpClient(handler) { BaseAddress = options.ApiBaseAddress },
            new FixedAccountSession(),
            options);
    }

    private static MicrosoftGraphOptions CreateOptions() => new()
    {
        Enabled = true,
        ClientId = "11111111-1111-1111-1111-111111111111",
        ControlledRecipient = "controlado@example.invalid",
        MaximumRetryAttempts = 1,
    };

    private static EmailEnvelope CreateEnvelope(params DispatchAttachmentSnapshot[] attachments) => new(
        Guid.Parse("22222222-2222-2222-2222-222222222222"),
        "phase7:synthetic:attempt:1",
        DispatchOperationMode.Send,
        new string('a', 64),
        "microsoft-graph://me",
        ["controlado@example.invalid"],
        [],
        "[FASE 7 — DESTINO CONTROLADO] Documento sintético",
        "Conteúdo sintético.",
        "<div>Conteúdo sintético.</div>",
        attachments,
        FakeDeliveryScenario.Success);

    private static DeliveryAttempt CreateAttempt(EmailEnvelope envelope) => new(
        Guid.NewGuid(),
        Guid.NewGuid(),
        envelope.DispatchItemId,
        Guid.NewGuid(),
        1,
        envelope.OperationMode,
        DeliveryAttemptState.Ambiguous,
        DispatchWorkflowOptions.MicrosoftGraphProviderKey,
        envelope.IdempotencyKey,
        envelope.DispatchFingerprint,
        null,
        null,
        "SEND_AMBIGUOUS",
        "Resultado sintético ambíguo.",
        DateTimeOffset.UtcNow,
        null);

    private async Task<DispatchAttachmentSnapshot> CreateAttachmentAsync(string name, int size)
    {
        var path = Path.Combine(temporaryDirectory, name);
        var bytes = new byte[size];
        RandomNumberGenerator.Fill(bytes);
        await File.WriteAllBytesAsync(path, bytes);
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        return new DispatchAttachmentSnapshot(
            Guid.NewGuid(),
            path,
            name,
            hash,
            bytes.Length,
            RecognizedDocumentType.Payroll);
    }

    private static HttpResponseMessage ThrowNetwork(ref int calls)
    {
        calls++;
        throw new HttpRequestException("Synthetic timeout.");
    }

    private static HttpResponseMessage RetryAfterResponse()
    {
        var response = Json(HttpStatusCode.TooManyRequests, "{}");
        response.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.Zero);
        return response;
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string json) => new(status)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };

    private sealed class FixedAccountSession(Exception? failure = null) : IEmailAccountSession
    {
        public string ProviderKey => DispatchWorkflowOptions.MicrosoftGraphProviderKey;

        public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new EmailAccountConnectionStatus(ProviderKey, true, true, "test@example.invalid", "Teste", null));

        public Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken) =>
            GetStatusAsync(cancellationToken);

        public Task DisconnectAsync(CancellationToken cancellationToken) => Task.CompletedTask;

        public Task<string> GetAccessTokenAsync(bool requireSendPermission, CancellationToken cancellationToken) =>
            failure is null ? Task.FromResult("test-access-token") : Task.FromException<string>(failure);
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
                request.RequestUri.Query,
                request.Content is null ? string.Empty : await request.Content.ReadAsStringAsync(cancellationToken),
                request.Headers.Authorization?.Parameter,
                request.Headers.TryGetValues("Prefer", out var prefer) ? prefer.Single() : null,
                request.Content?.Headers.ContentRange?.ToString());
            Requests.Add(snapshot);
            return responder(snapshot, Requests.Count);
        }
    }

    private sealed record RequestSnapshot(
        string Method,
        string Path,
        string Query,
        string Body,
        string? BearerToken,
        string? Prefer,
        string? ContentRange);
}
