using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using MimeKit;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class GmailEmailProvider(
    HttpClient httpClient,
    IEmailAccountSession accountSession,
    GmailOptions options) : IEmailProvider
{
    public string ProviderKey => DispatchWorkflowOptions.GmailProviderKey;

    public async Task<EmailProviderAccount> GetAccountAsync(CancellationToken cancellationToken)
    {
        var status = await accountSession.GetStatusAsync(cancellationToken);
        return new EmailProviderAccount(
            ProviderKey,
            status.AccountId ?? "google-gmail://me",
            status.DisplayName,
            status.IsConfigured && status.IsConnected);
    }

    public Task<EmailProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken) =>
        Task.FromResult(new EmailProviderCapabilities(
            SupportsDrafts: true,
            SupportsSending: true,
            SupportsReconciliation: true,
            options.MaximumAttachmentBytes));

    public async Task<EmailProviderResult> CreateDraftAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken)
    {
        try
        {
            await ValidateEnvelopeAsync(envelope, cancellationToken);
            return await CreateOrRecoverDraftAsync(envelope, cancellationToken);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception);
        }
        catch (GmailApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true);
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Ambiguous("GMAIL_DRAFT_RESULT_UNKNOWN", null);
        }
        catch (IOException)
        {
            return Permanent("ATTACHMENT_READ_FAILED", "Não foi possível validar ou ler um anexo aprovado.");
        }
    }

    public async Task<EmailProviderResult> SendAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken)
    {
        string? draftId = null;
        try
        {
            await ValidateEnvelopeAsync(envelope, cancellationToken);
            if (!options.EmailSendEnabled)
            {
                throw new EmailAccountSessionException(
                    "EMAIL_SEND_DISABLED",
                    "O envio pelo Gmail está desligado nesta instalação.");
            }

            var draft = await CreateOrRecoverDraftAsync(envelope, cancellationToken);
            if (draft.State != DeliveryAttemptState.DraftCreated ||
                string.IsNullOrWhiteSpace(draft.ProviderDraftId))
            {
                return draft;
            }

            draftId = draft.ProviderDraftId;
            using var response = await SendGmailAsync(
                () => new HttpRequestMessage(HttpMethod.Post, "users/me/drafts/send")
                {
                    Content = JsonContent.Create(new { id = draftId }),
                },
                retrySafe: false,
                requireSendPermission: true,
                cancellationToken);
            if (response.StatusCode != HttpStatusCode.OK)
            {
                throw GmailApiException.From(response.StatusCode, response.Headers.RetryAfter);
            }

            using var document = await JsonDocument.ParseAsync(
                await response.Content.ReadAsStreamAsync(cancellationToken),
                cancellationToken: cancellationToken);
            var messageId = RequiredString(document.RootElement, "id");
            return new EmailProviderResult(
                DeliveryAttemptState.AcceptedByProvider,
                messageId,
                draftId,
                null,
                null);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception, draftId);
        }
        catch (GmailApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true, draftId);
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Ambiguous("GMAIL_SEND_RESULT_UNKNOWN", draftId);
        }
        catch (IOException)
        {
            return Permanent(
                "ATTACHMENT_READ_FAILED",
                "Não foi possível validar ou ler um anexo aprovado.",
                draftId);
        }
    }

    public async Task<EmailProviderResult> ReconcileAsync(
        DeliveryAttempt attempt,
        CancellationToken cancellationToken)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(attempt.ProviderDraftId))
            {
                using var response = await SendGmailAsync(
                    () => new HttpRequestMessage(
                        HttpMethod.Get,
                        $"users/me/drafts/{Uri.EscapeDataString(attempt.ProviderDraftId)}?format=minimal"),
                    retrySafe: true,
                    requireSendPermission: false,
                    cancellationToken);
                if (response.StatusCode == HttpStatusCode.OK)
                {
                    return DraftConfirmed(attempt.ProviderDraftId);
                }

                if (response.StatusCode == HttpStatusCode.NotFound)
                {
                    return Ambiguous("GMAIL_SENT_OR_DELETED_UNKNOWN", attempt.ProviderDraftId);
                }

                throw GmailApiException.From(response.StatusCode, response.Headers.RetryAfter);
            }

            var draft = await FindDraftByMessageIdAsync(CreateMessageId(attempt), cancellationToken);
            return draft is null
                ? Ambiguous("GMAIL_DRAFT_NOT_FOUND", null)
                : DraftConfirmed(draft.DraftId);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception, attempt.ProviderDraftId);
        }
        catch (GmailApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true, attempt.ProviderDraftId);
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Ambiguous("GMAIL_RECONCILIATION_UNAVAILABLE", attempt.ProviderDraftId);
        }
    }

    private async Task<EmailProviderResult> CreateOrRecoverDraftAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken)
    {
        var messageId = CreateMessageId(envelope);
        var existing = await FindDraftByMessageIdAsync(messageId, cancellationToken);
        if (existing is not null)
        {
            return new EmailProviderResult(
                DeliveryAttemptState.DraftCreated,
                existing.MessageId,
                existing.DraftId,
                null,
                null);
        }

        var raw = await CreateRawMimeMessageAsync(envelope, messageId, cancellationToken);
        try
        {
            using var response = await SendGmailAsync(
                () => new HttpRequestMessage(HttpMethod.Post, "users/me/drafts")
                {
                    Content = JsonContent.Create(new
                    {
                        message = new { raw },
                    }),
                },
                retrySafe: false,
                requireSendPermission: false,
                cancellationToken);
            if (response.StatusCode != HttpStatusCode.OK)
            {
                throw GmailApiException.From(response.StatusCode, response.Headers.RetryAfter);
            }

            using var document = await JsonDocument.ParseAsync(
                await response.Content.ReadAsStreamAsync(cancellationToken),
                cancellationToken: cancellationToken);
            var draftId = RequiredString(document.RootElement, "id");
            var providerMessageId = document.RootElement.TryGetProperty("message", out var message)
                ? RequiredString(message, "id")
                : null;
            return new EmailProviderResult(
                DeliveryAttemptState.DraftCreated,
                providerMessageId,
                draftId,
                null,
                null);
        }
        catch (GmailApiException)
        {
            throw;
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Ambiguous("GMAIL_DRAFT_RESULT_UNKNOWN", null);
        }
    }

    private async Task<GmailDraft?> FindDraftByMessageIdAsync(
        string messageId,
        CancellationToken cancellationToken)
    {
        var query = $"rfc822msgid:{messageId}";
        var uri = $"users/me/drafts?maxResults=2&q={Uri.EscapeDataString(query)}";
        using var response = await SendGmailAsync(
            () => new HttpRequestMessage(HttpMethod.Get, uri),
            retrySafe: true,
            requireSendPermission: false,
            cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw GmailApiException.From(response.StatusCode, response.Headers.RetryAfter);
        }

        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        var root = document.RootElement;
        if (!root.TryGetProperty("drafts", out var drafts))
        {
            return null;
        }

        var matches = drafts.EnumerateArray().ToArray();
        if (matches.Length > 1 || root.TryGetProperty("nextPageToken", out _))
        {
            throw GmailApiException.From(HttpStatusCode.Conflict, null);
        }

        if (matches.Length == 0)
        {
            return null;
        }

        var message = matches[0].GetProperty("message");
        return new GmailDraft(
            RequiredString(matches[0], "id"),
            RequiredString(message, "id"));
    }

    private async Task<string> CreateRawMimeMessageAsync(
        EmailEnvelope envelope,
        string messageId,
        CancellationToken cancellationToken)
    {
        var account = await accountSession.GetStatusAsync(cancellationToken);
        if (!account.IsConnected || string.IsNullOrWhiteSpace(account.AccountId))
        {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "Conecte uma conta Google dedicada.");
        }

        var message = new MimeMessage();
        message.From.Add(MailboxAddress.Parse(account.AccountId));
        foreach (var recipient in envelope.To)
        {
            message.To.Add(MailboxAddress.Parse(recipient));
        }

        message.Subject = envelope.Subject;
        message.MessageId = messageId.Trim('<', '>');
        message.Headers.Add("X-Folhas-Idempotency", CreateOperationKey(envelope));
        var body = new BodyBuilder
        {
            TextBody = envelope.TextBody,
            HtmlBody = envelope.HtmlBody,
        };
        foreach (var attachment in envelope.Attachments)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await ValidateAttachmentAsync(attachment, cancellationToken);
            body.Attachments.Add(
                attachment.FileName,
                await File.ReadAllBytesAsync(attachment.LocalPath, cancellationToken),
                new ContentType("application", "pdf"));
        }

        message.Body = body.ToMessageBody();
        await using var stream = new MemoryStream();
        await message.WriteToAsync(stream, cancellationToken);
        return Base64Url(stream.ToArray());
    }

    private async Task<HttpResponseMessage> SendGmailAsync(
        Func<HttpRequestMessage> requestFactory,
        bool retrySafe,
        bool requireSendPermission,
        CancellationToken cancellationToken)
    {
        for (var retry = 0; ; retry++)
        {
            var token = await accountSession.GetAccessTokenAsync(requireSendPermission, cancellationToken);
            using var request = requestFactory();
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            try
            {
                var response = await httpClient.SendAsync(
                    request,
                    HttpCompletionOption.ResponseHeadersRead,
                    cancellationToken);
                if (!retrySafe || !IsTransient(response.StatusCode) || retry >= options.MaximumRetryAttempts)
                {
                    return response;
                }

                var delay = GetRetryDelay(response.Headers.RetryAfter, retry);
                response.Dispose();
                await Task.Delay(delay, cancellationToken);
            }
            catch (HttpRequestException) when (retrySafe && retry < options.MaximumRetryAttempts)
            {
                await Task.Delay(GetRetryDelay(null, retry), cancellationToken);
            }
        }
    }

    private async Task ValidateEnvelopeAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        if (!options.IsConfigured || accountSession.ProviderKey != ProviderKey)
        {
            throw new EmailAccountSessionException("GMAIL_NOT_CONFIGURED", "O Gmail não está configurado.");
        }

        if (envelope.Scenario != FakeDeliveryScenario.Success)
        {
            throw new EmailAccountSessionException(
                "GMAIL_FAKE_SCENARIO_FORBIDDEN",
                "Cenários simulados pertencem somente ao modo local seguro.");
        }

        var recipients = envelope.To.Concat(envelope.Cc).ToArray();
        if (recipients.Length != 1 || envelope.To.Count != 1 || envelope.Cc.Count != 0 ||
            !string.Equals(recipients[0], options.ControlledRecipient.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            throw new EmailAccountSessionException(
                "GMAIL_RECIPIENT_NOT_CONTROLLED",
                "A Fase 8 permite somente o destinatário Google controlado.");
        }

        if (envelope.Attachments.Sum(item => item.FileSizeBytes) > options.MaximumAttachmentBytes)
        {
            throw new IOException("Attachment limit exceeded.");
        }

        foreach (var attachment in envelope.Attachments)
        {
            await ValidateAttachmentAsync(attachment, cancellationToken);
        }
    }

    private static async Task ValidateAttachmentAsync(
        DispatchAttachmentSnapshot attachment,
        CancellationToken cancellationToken)
    {
        var file = new FileInfo(attachment.LocalPath);
        if (!file.Exists || file.Length != attachment.FileSizeBytes)
        {
            throw new IOException("Attachment is missing or changed.");
        }

        await using var stream = new FileStream(
            attachment.LocalPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            81920,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
        if (!string.Equals(hash, attachment.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException("Attachment hash changed.");
        }
    }

    private static string CreateOperationKey(EmailEnvelope envelope) =>
        $"folhas:{envelope.OperationMode}:{envelope.DispatchItemId:N}:{envelope.DispatchFingerprint}";

    private static string CreateMessageId(EmailEnvelope envelope) =>
        CreateMessageId(envelope.OperationMode, envelope.DispatchItemId, envelope.DispatchFingerprint);

    private static string CreateMessageId(DeliveryAttempt attempt) =>
        CreateMessageId(attempt.Mode, attempt.DispatchItemId, attempt.DispatchFingerprint);

    private static string CreateMessageId(
        DispatchOperationMode mode,
        Guid itemId,
        string fingerprint) =>
        $"<folhas.{mode.ToString().ToLowerInvariant()}.{itemId:N}.{fingerprint[..Math.Min(32, fingerprint.Length)]}@folhas.invalid>";

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string RequiredString(JsonElement root, string property)
    {
        var value = root.GetProperty(property).GetString();
        return string.IsNullOrWhiteSpace(value) ? throw new JsonException($"Missing {property}.") : value;
    }

    private static bool IsTransient(HttpStatusCode statusCode) =>
        statusCode is HttpStatusCode.TooManyRequests or
            HttpStatusCode.RequestTimeout or
            HttpStatusCode.InternalServerError or
            HttpStatusCode.BadGateway or
            HttpStatusCode.ServiceUnavailable or
            HttpStatusCode.GatewayTimeout;

    private static TimeSpan GetRetryDelay(RetryConditionHeaderValue? retryAfter, int retry)
    {
        if (retryAfter?.Delta is { } delta)
        {
            return delta < TimeSpan.Zero ? TimeSpan.Zero : delta;
        }

        if (retryAfter?.Date is { } date)
        {
            var remaining = date - DateTimeOffset.UtcNow;
            return remaining < TimeSpan.Zero ? TimeSpan.Zero : remaining;
        }

        var exponentialMilliseconds = Math.Min(30_000, 500 * (1 << Math.Min(retry, 5)));
        return TimeSpan.FromMilliseconds(exponentialMilliseconds + RandomNumberGenerator.GetInt32(0, 251));
    }

    private static EmailProviderResult DraftConfirmed(string draftId) => new(
        DeliveryAttemptState.DraftCreated,
        null,
        draftId,
        "GMAIL_DRAFT_CONFIRMED_NOT_SENT",
        "O Gmail confirmou que a mensagem permanece como rascunho; o envio exige nova ação humana explícita.");

    private static EmailProviderResult AuthenticationFailure(
        EmailAccountSessionException exception,
        string? draftId = null) => exception.Code == "AUTH_TEMPORARILY_UNAVAILABLE"
            ? new EmailProviderResult(
                DeliveryAttemptState.FailedTransient,
                null,
                draftId,
                exception.Code,
                exception.Message)
            : new EmailProviderResult(
                DeliveryAttemptState.FailedPermanent,
                null,
                draftId,
                exception.Code,
                exception.Message);

    private static EmailProviderResult MapFailure(
        GmailApiException exception,
        bool operationMayHaveCompleted,
        string? draftId = null)
    {
        if (exception.StatusCode is HttpStatusCode.Unauthorized)
        {
            return Permanent("AUTH_REVOKED", "A autorização Google foi rejeitada ou revogada.", draftId);
        }

        if (exception.StatusCode is HttpStatusCode.Forbidden)
        {
            return Permanent("PROVIDER_REJECTED", "O Gmail recusou a permissão para esta operação.", draftId);
        }

        if (exception.StatusCode is HttpStatusCode.TooManyRequests)
        {
            var retry = exception.RetryAfter?.TotalSeconds.ToString("0", CultureInfo.InvariantCulture);
            return new EmailProviderResult(
                DeliveryAttemptState.FailedTransient,
                null,
                draftId,
                "PROVIDER_THROTTLED",
                retry is null ? "O Gmail limitou a operação temporariamente." : $"O Gmail solicitou aguardar {retry} segundo(s).");
        }

        if (operationMayHaveCompleted && IsTransient(exception.StatusCode))
        {
            return Ambiguous("GMAIL_RESULT_UNKNOWN", draftId);
        }

        if (exception.StatusCode is HttpStatusCode.BadRequest or
            HttpStatusCode.RequestEntityTooLarge or
            HttpStatusCode.UnsupportedMediaType or
            HttpStatusCode.UnprocessableEntity or
            HttpStatusCode.Conflict)
        {
            return Permanent("PROVIDER_REJECTED", "O Gmail rejeitou o conteúdo ou encontrou uma duplicidade.", draftId);
        }

        return operationMayHaveCompleted
            ? Ambiguous("GMAIL_RESULT_UNKNOWN", draftId)
            : Permanent("PROVIDER_REJECTED", "O Gmail rejeitou a operação.", draftId);
    }

    private static EmailProviderResult Permanent(string code, string message, string? draftId = null) => new(
        DeliveryAttemptState.FailedPermanent,
        null,
        draftId,
        code,
        message);

    private static EmailProviderResult Ambiguous(string code, string? draftId) => new(
        DeliveryAttemptState.Ambiguous,
        null,
        draftId,
        code,
        "O resultado do Gmail permanece desconhecido; não tente reenviar sem reconciliação.");

    private sealed record GmailDraft(string DraftId, string MessageId);

    private sealed class GmailApiException(
        HttpStatusCode statusCode,
        TimeSpan? retryAfter) : Exception("Gmail API request failed.")
    {
        public HttpStatusCode StatusCode { get; } = statusCode;

        public TimeSpan? RetryAfter { get; } = retryAfter;

        public static GmailApiException From(
            HttpStatusCode statusCode,
            RetryConditionHeaderValue? retryAfter) => new(
            statusCode,
            retryAfter?.Delta ?? (retryAfter?.Date is { } date ? date - DateTimeOffset.UtcNow : null));
    }
}
