using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Dispatch;
using MimeKit;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase8GmailProviderTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        "folhas-phase8-gmail",
        Guid.NewGuid().ToString("N"));

    public Phase8GmailProviderTests() => Directory.CreateDirectory(temporaryDirectory);

    [Fact]
    public void GmailConfigurationUsesComposeOnlyAndRejectsNonDesktopClientId()
    {
        var options = CreateOptions();

        Assert.True(options.IsConfigured);
        Assert.Equal([GmailOptions.ComposeScope], options.Scopes);
        Assert.DoesNotContain("https://mail.google.com/", options.Scopes);
        options.ClientId = "not-a-desktop-client";
        Assert.False(options.IsConfigured);
    }

    [Fact]
    public async Task DraftFirstSendCreatesUtf8MimeAttachmentAndReturnsProviderIds()
    {
        var attachment = await CreateAttachmentAsync("folha-sintetica.pdf", 2048);
        var handler = new RecordingHandler((request, _) => (request.Method, request.Path) switch
        {
            ("GET", "/gmail/v1/users/me/drafts") => Json(HttpStatusCode.OK, "{}"),
            ("POST", "/gmail/v1/users/me/drafts") => Json(
                HttpStatusCode.OK,
                "{\"id\":\"draft-1\",\"message\":{\"id\":\"message-draft-1\"}}"),
            ("POST", "/gmail/v1/users/me/drafts/send") => Json(
                HttpStatusCode.OK,
                "{\"id\":\"message-sent-1\",\"threadId\":\"thread-1\"}"),
            _ => throw new InvalidOperationException($"Unexpected Gmail request: {request.Method} {request.Path}"),
        });
        var provider = CreateProvider(handler);

        var result = await provider.SendAsync(CreateEnvelope(attachment), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.AcceptedByProvider, result.State);
        Assert.Equal("message-sent-1", result.ProviderMessageId);
        Assert.Equal("draft-1", result.ProviderDraftId);
        Assert.Equal(3, handler.Requests.Count);
        Assert.All(handler.Requests, request => Assert.Equal("test-access-token", request.BearerToken));
        var create = handler.Requests.Single(request =>
            request.Method == "POST" && request.Path == "/gmail/v1/users/me/drafts");
        using var payload = JsonDocument.Parse(create.Body);
        var raw = payload.RootElement.GetProperty("message").GetProperty("raw").GetString();
        Assert.NotNull(raw);
        var bytes = DecodeBase64Url(raw);
        using var stream = new MemoryStream(bytes);
        var mime = await MimeMessage.LoadAsync(stream, CancellationToken.None);
        Assert.Equal("conta.teste@example.invalid", Assert.Single(mime.From.Mailboxes).Address);
        Assert.Equal("controlado@example.invalid", Assert.Single(mime.To.Mailboxes).Address);
        Assert.Equal("Conteúdo sintético com acentuação.", mime.TextBody);
        Assert.Contains("Conteúdo sintético", mime.HtmlBody, StringComparison.Ordinal);
        Assert.StartsWith("folhas.send.", mime.MessageId, StringComparison.Ordinal);
        Assert.Contains("folhas:Send:", mime.Headers["X-Folhas-Idempotency"], StringComparison.Ordinal);
        var mimeAttachment = Assert.Single(mime.Attachments.Cast<MimePart>());
        Assert.Equal("folha-sintetica.pdf", mimeAttachment.FileName);
        Assert.Equal("application/pdf", mimeAttachment.ContentType.MimeType);
        Assert.DoesNotContain("cliente-real@example.com", create.Body, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AmbiguousSendIsNeverRetriedAndExistingDraftCanBeReconciled()
    {
        var sendCalls = 0;
        var handler = new RecordingHandler((request, _) => (request.Method, request.Path) switch
        {
            ("GET", "/gmail/v1/users/me/drafts") => Json(HttpStatusCode.OK, "{}"),
            ("POST", "/gmail/v1/users/me/drafts") => Json(
                HttpStatusCode.OK,
                "{\"id\":\"draft-2\",\"message\":{\"id\":\"message-draft-2\"}}"),
            ("POST", "/gmail/v1/users/me/drafts/send") => ThrowNetwork(ref sendCalls),
            ("GET", "/gmail/v1/users/me/drafts/draft-2") => Json(
                HttpStatusCode.OK,
                "{\"id\":\"draft-2\",\"message\":{\"id\":\"message-draft-2\"}}"),
            _ => throw new InvalidOperationException($"Unexpected Gmail request: {request.Method} {request.Path}"),
        });
        var provider = CreateProvider(handler);
        var envelope = CreateEnvelope();

        var result = await provider.SendAsync(envelope, CancellationToken.None);
        var reconciled = await provider.ReconcileAsync(
            CreateAttempt(envelope, result.ProviderDraftId),
            CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.Ambiguous, result.State);
        Assert.Equal("GMAIL_SEND_RESULT_UNKNOWN", result.ErrorCode);
        Assert.Equal(1, sendCalls);
        Assert.Equal(DeliveryAttemptState.DraftCreated, reconciled.State);
        Assert.Equal("GMAIL_DRAFT_CONFIRMED_NOT_SENT", reconciled.ErrorCode);
        Assert.Equal("draft-2", reconciled.ProviderDraftId);
    }

    [Fact]
    public async Task ControlledRecipientAndRevokedAuthorizationFailBeforeGmailCall()
    {
        var handler = new RecordingHandler((_, _) => throw new InvalidOperationException("HTTP must not be called."));
        var provider = CreateProvider(handler);
        var unsafeEnvelope = CreateEnvelope() with { To = ["cliente-real@example.com"] };

        var rejected = await provider.SendAsync(unsafeEnvelope, CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.FailedPermanent, rejected.State);
        Assert.Equal("GMAIL_RECIPIENT_NOT_CONTROLLED", rejected.ErrorCode);
        Assert.Empty(handler.Requests);

        var disabledOptions = CreateOptions();
        disabledOptions.EmailSendEnabled = false;
        var disabledProvider = new GmailEmailProvider(
            new HttpClient(handler) { BaseAddress = disabledOptions.ApiBaseAddress },
            new FixedAccountSession(),
            disabledOptions);
        var disabled = await disabledProvider.SendAsync(CreateEnvelope(), CancellationToken.None);
        Assert.Equal(DeliveryAttemptState.FailedPermanent, disabled.State);
        Assert.Equal("EMAIL_SEND_DISABLED", disabled.ErrorCode);
        Assert.Empty(handler.Requests);

        var revokedProvider = new GmailEmailProvider(
            new HttpClient(handler) { BaseAddress = CreateOptions().ApiBaseAddress },
            new FixedAccountSession(new EmailAccountSessionException("AUTH_REVOKED", "Revogada.")),
            CreateOptions());
        var revoked = await revokedProvider.CreateDraftAsync(CreateEnvelope(), CancellationToken.None);

        Assert.Equal(DeliveryAttemptState.FailedPermanent, revoked.State);
        Assert.Equal("AUTH_REVOKED", revoked.ErrorCode);
        Assert.Empty(handler.Requests);

        var temporaryProvider = new GmailEmailProvider(
            new HttpClient(handler) { BaseAddress = CreateOptions().ApiBaseAddress },
            new FixedAccountSession(new EmailAccountSessionException(
                "AUTH_TEMPORARILY_UNAVAILABLE",
                "Indisponibilidade sintética.")),
            CreateOptions());
        var temporary = await temporaryProvider.CreateDraftAsync(CreateEnvelope(), CancellationToken.None);
        Assert.Equal(DeliveryAttemptState.FailedTransient, temporary.State);
        Assert.Equal("AUTH_TEMPORARILY_UNAVAILABLE", temporary.ErrorCode);
        Assert.Empty(handler.Requests);
    }

    public void Dispose()
    {
        Directory.Delete(temporaryDirectory, true);
        GC.SuppressFinalize(this);
    }

    private static GmailOptions CreateOptions() => new()
    {
        Enabled = true,
        EmailSendEnabled = true,
        ClientId = "synthetic-client.apps.googleusercontent.com",
        ControlledRecipient = "controlado@example.invalid",
        ApiBaseAddress = new Uri("https://gmail.example.invalid/gmail/v1/"),
        MaximumRetryAttempts = 1,
    };

    private static GmailEmailProvider CreateProvider(RecordingHandler handler)
    {
        var options = CreateOptions();
        return new GmailEmailProvider(
            new HttpClient(handler) { BaseAddress = options.ApiBaseAddress },
            new FixedAccountSession(),
            options);
    }

    private static EmailEnvelope CreateEnvelope(params DispatchAttachmentSnapshot[] attachments) => new(
        Guid.Parse("22222222-2222-2222-2222-222222222222"),
        "phase8:synthetic:attempt:1",
        DispatchOperationMode.Send,
        new string('b', 64),
        "google-gmail://me",
        ["controlado@example.invalid"],
        [],
        "[FASE 8 — DESTINO CONTROLADO] Documento sintético",
        "Conteúdo sintético com acentuação.",
        "<div>Conteúdo sintético com acentuação.</div>",
        attachments,
        FakeDeliveryScenario.Success);

    private static DeliveryAttempt CreateAttempt(EmailEnvelope envelope, string? draftId) => new(
        Guid.NewGuid(),
        Guid.NewGuid(),
        envelope.DispatchItemId,
        Guid.NewGuid(),
        1,
        envelope.OperationMode,
        DeliveryAttemptState.Ambiguous,
        DispatchWorkflowOptions.GmailProviderKey,
        envelope.IdempotencyKey,
        envelope.DispatchFingerprint,
        null,
        draftId,
        "GMAIL_SEND_RESULT_UNKNOWN",
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

    private static byte[] DecodeBase64Url(string value)
    {
        var normalized = value.Replace('-', '+').Replace('_', '/');
        normalized = normalized.PadRight(normalized.Length + (4 - normalized.Length % 4) % 4, '=');
        return Convert.FromBase64String(normalized);
    }

    private static HttpResponseMessage ThrowNetwork(ref int calls)
    {
        calls++;
        throw new HttpRequestException("Synthetic timeout.");
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string json) => new(status)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };

    private sealed class FixedAccountSession(Exception? failure = null) : IEmailAccountSession
    {
        public string ProviderKey => DispatchWorkflowOptions.GmailProviderKey;

        public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new EmailAccountConnectionStatus(
                ProviderKey,
                true,
                true,
                "conta.teste@example.invalid",
                "Conta Google de teste",
                null));

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
                request.Headers.Authorization?.Parameter);
            Requests.Add(snapshot);
            return responder(snapshot, Requests.Count);
        }
    }

    private sealed record RequestSnapshot(
        string Method,
        string Path,
        string Query,
        string Body,
        string? BearerToken);
}
