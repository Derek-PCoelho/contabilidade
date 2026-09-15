using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class MicrosoftGraphEmailProvider(
    HttpClient httpClient,
    IEmailAccountSession accountSession,
    MicrosoftGraphOptions options) : IEmailProvider
{
    private const int SimpleAttachmentLimitBytes = 3 * 1024 * 1024;
    private const int UploadChunkSizeBytes = 10 * 320 * 1024;
    private const string IdempotencyPropertyId =
        "String {66f5a359-4659-4830-9070-00040ec6ac6e} Name FolhasIdempotency";

    public string ProviderKey => DispatchWorkflowOptions.MicrosoftGraphProviderKey;

    public async Task<EmailProviderAccount> GetAccountAsync(CancellationToken cancellationToken)
    {
        var status = await accountSession.GetStatusAsync(cancellationToken);
        return new EmailProviderAccount(
            ProviderKey,
            status.AccountId ?? "microsoft-graph://me",
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
            ValidateEnvelope(envelope);
            return await CreateOrRecoverDraftAsync(envelope, cancellationToken);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception);
        }
        catch (GraphApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true);
        }
        catch (HttpRequestException)
        {
            return Ambiguous("PROVIDER_RESULT_UNKNOWN");
        }
        catch (JsonException)
        {
            return Ambiguous("PROVIDER_RESPONSE_INVALID");
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
            ValidateEnvelope(envelope);
            var draft = await CreateOrRecoverDraftAsync(envelope, cancellationToken);
            if (draft.State == DeliveryAttemptState.AcceptedByProvider)
            {
                return draft;
            }

            if (draft.State != DeliveryAttemptState.DraftCreated || string.IsNullOrWhiteSpace(draft.ProviderDraftId))
            {
                return draft;
            }

            draftId = draft.ProviderDraftId;
            using var response = await SendGraphAsync(
                () => new HttpRequestMessage(
                    HttpMethod.Post,
                    $"me/messages/{Uri.EscapeDataString(draftId)}/send")
                {
                    Content = new ByteArrayContent([]),
                },
                retrySafe: false,
                cancellationToken,
                requireSendPermission: true);
            if (response.StatusCode == HttpStatusCode.Accepted)
            {
                return new EmailProviderResult(
                    DeliveryAttemptState.AcceptedByProvider,
                    draftId,
                    draftId,
                    null,
                    null);
            }

            throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception, draftId);
        }
        catch (GraphApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true, draftId);
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                draftId,
                "SEND_AMBIGUOUS",
                "O resultado do envio Microsoft é desconhecido; reconcilie antes de tentar novamente.");
        }
    }

    public async Task<EmailProviderResult> ReconcileAsync(
        DeliveryAttempt attempt,
        CancellationToken cancellationToken)
    {
        try
        {
            var message = await FindByOperationKeyAsync(CreateOperationKey(attempt), cancellationToken);
            if (message is null)
            {
                return Ambiguous("GRAPH_MESSAGE_NOT_FOUND");
            }

            return message.IsDraft
                ? new EmailProviderResult(
                    DeliveryAttemptState.DraftCreated,
                    null,
                    message.Id,
                    "GRAPH_DRAFT_CONFIRMED_NOT_SENT",
                    "O Graph confirmou que a mensagem permanece como rascunho; uma nova execução exige ação humana explícita.")
                : new EmailProviderResult(
                    DeliveryAttemptState.AcceptedByProvider,
                    message.Id,
                    message.Id,
                    null,
                    null);
        }
        catch (EmailAccountSessionException exception)
        {
            return AuthenticationFailure(exception, attempt.ProviderDraftId);
        }
        catch (GraphApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true, attempt.ProviderDraftId);
        }
        catch (Exception exception) when (
            exception is HttpRequestException or TaskCanceledException or JsonException)
        {
            return Ambiguous("GRAPH_RECONCILIATION_UNAVAILABLE");
        }
    }

    private async Task<EmailProviderResult> CreateOrRecoverDraftAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken)
    {
        var operationKey = CreateOperationKey(envelope);
        var existing = await FindByOperationKeyAsync(operationKey, cancellationToken);
        if (existing is { IsDraft: false })
        {
            return new EmailProviderResult(
                DeliveryAttemptState.AcceptedByProvider,
                existing.Id,
                existing.Id,
                null,
                null);
        }

        var draftId = existing?.Id;
        try
        {
            if (string.IsNullOrWhiteSpace(draftId))
            {
                var payload = CreateMessagePayload(envelope, operationKey);
                using var response = await SendGraphAsync(
                    () => new HttpRequestMessage(HttpMethod.Post, "me/messages")
                    {
                        Content = JsonContent.Create(payload),
                    },
                    retrySafe: false,
                    cancellationToken);
                if (response.StatusCode != HttpStatusCode.Created)
                {
                    throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
                }

                using var document = await JsonDocument.ParseAsync(
                    await response.Content.ReadAsStreamAsync(cancellationToken),
                    cancellationToken: cancellationToken);
                draftId = document.RootElement.GetProperty("id").GetString();
                if (string.IsNullOrWhiteSpace(draftId))
                {
                    throw new GraphApiException(HttpStatusCode.BadGateway, null);
                }
            }

            await EnsureAttachmentsAsync(draftId, envelope.Attachments, cancellationToken);
            return new EmailProviderResult(
                DeliveryAttemptState.DraftCreated,
                null,
                draftId,
                null,
                null);
        }
        catch (GraphApiException exception)
        {
            return MapFailure(exception, operationMayHaveCompleted: true, draftId);
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException)
        {
            return new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                draftId,
                "PROVIDER_RESULT_UNKNOWN",
                "O resultado da criação do rascunho/anexo é desconhecido; reconcilie antes de repetir.");
        }
    }

    private async Task<GraphMessage?> FindByOperationKeyAsync(
        string operationKey,
        CancellationToken cancellationToken)
    {
        var filter = $"singleValueExtendedProperties/Any(ep: ep/id eq '{IdempotencyPropertyId}' and ep/value eq '{operationKey}')";
        var expand = $"singleValueExtendedProperties($filter=id eq '{IdempotencyPropertyId}')";
        var uri = $"me/messages?$select=id,isDraft,sentDateTime&$top=2&$filter={Uri.EscapeDataString(filter)}&$expand={Uri.EscapeDataString(expand)}";
        using var response = await SendGraphAsync(
            () => new HttpRequestMessage(HttpMethod.Get, uri),
            retrySafe: true,
            cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
        }

        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        var matches = document.RootElement.GetProperty("value").EnumerateArray().ToArray();
        if (matches.Length > 1 || document.RootElement.TryGetProperty("@odata.nextLink", out _))
        {
            throw new GraphApiException(HttpStatusCode.Conflict, null);
        }

        if (matches.Length == 0)
        {
            return null;
        }

        return new GraphMessage(
            matches[0].GetProperty("id").GetString() ?? throw new JsonException("Graph message id missing."),
            matches[0].GetProperty("isDraft").GetBoolean());
    }

    private async Task EnsureAttachmentsAsync(
        string draftId,
        IReadOnlyList<DispatchAttachmentSnapshot> attachments,
        CancellationToken cancellationToken)
    {
        var existingContentIds = await GetAttachmentContentIdsAsync(draftId, cancellationToken);
        foreach (var attachment in attachments)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await ValidateAttachmentAsync(attachment, cancellationToken);
            var contentId = CreateAttachmentContentId(attachment.Sha256);
            if (existingContentIds.Contains(contentId))
            {
                continue;
            }

            if (attachment.FileSizeBytes < SimpleAttachmentLimitBytes)
            {
                await AddSimpleAttachmentAsync(draftId, attachment, contentId, cancellationToken);
            }
            else
            {
                await UploadLargeAttachmentAsync(draftId, attachment, contentId, cancellationToken);
            }

            existingContentIds.Add(contentId);
        }
    }

    private async Task<HashSet<string>> GetAttachmentContentIdsAsync(
        string draftId,
        CancellationToken cancellationToken)
    {
        var contentIds = new HashSet<string>(StringComparer.Ordinal);
        string? requestUri =
            $"me/messages/{Uri.EscapeDataString(draftId)}/attachments?$select=id,name,size,contentId&$top=100";
        while (requestUri is not null)
        {
            using var response = await SendGraphAsync(
                () => new HttpRequestMessage(HttpMethod.Get, requestUri),
                retrySafe: true,
                cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
            }

            using var document = await JsonDocument.ParseAsync(
                await response.Content.ReadAsStreamAsync(cancellationToken),
                cancellationToken: cancellationToken);
            foreach (var item in document.RootElement.GetProperty("value").EnumerateArray())
            {
                if (item.TryGetProperty("contentId", out var value) && value.ValueKind == JsonValueKind.String)
                {
                    contentIds.Add(value.GetString()!);
                }
            }

            requestUri = GetValidatedNextLink(document.RootElement);
        }

        return contentIds;
    }

    private string? GetValidatedNextLink(JsonElement root)
    {
        if (!root.TryGetProperty("@odata.nextLink", out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        var nextLink = value.GetString();
        if (!Uri.TryCreate(nextLink, UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps ||
            !string.Equals(uri.Host, options.ApiBaseAddress.Host, StringComparison.OrdinalIgnoreCase))
        {
            throw new GraphApiException(HttpStatusCode.BadGateway, null);
        }

        return uri.AbsoluteUri;
    }

    private async Task AddSimpleAttachmentAsync(
        string draftId,
        DispatchAttachmentSnapshot attachment,
        string contentId,
        CancellationToken cancellationToken)
    {
        var bytes = await File.ReadAllBytesAsync(attachment.LocalPath, cancellationToken);
        try
        {
            ValidateAttachmentBytes(attachment, bytes);
            var payload = new Dictionary<string, object?>
            {
                ["@odata.type"] = "#microsoft.graph.fileAttachment",
                ["name"] = attachment.FileName,
                ["contentType"] = "application/pdf",
                ["isInline"] = false,
                ["contentId"] = contentId,
                ["contentBytes"] = Convert.ToBase64String(bytes),
            };
            using var response = await SendGraphAsync(
                () => new HttpRequestMessage(
                    HttpMethod.Post,
                    $"me/messages/{Uri.EscapeDataString(draftId)}/attachments")
                {
                    Content = JsonContent.Create(payload),
                },
                retrySafe: false,
                cancellationToken);
            if (response.StatusCode != HttpStatusCode.Created)
            {
                throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    private async Task UploadLargeAttachmentAsync(
        string draftId,
        DispatchAttachmentSnapshot attachment,
        string contentId,
        CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(
            attachment.LocalPath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            UploadChunkSizeBytes,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        await ValidateAttachmentStreamAsync(attachment, stream, cancellationToken);

        var payload = new Dictionary<string, object?>
        {
            ["AttachmentItem"] = new Dictionary<string, object?>
            {
                ["attachmentType"] = "file",
                ["name"] = attachment.FileName,
                ["size"] = attachment.FileSizeBytes,
                ["contentType"] = "application/pdf",
                ["isInline"] = false,
                ["contentId"] = contentId,
            },
        };
        using var sessionResponse = await SendGraphAsync(
            () => new HttpRequestMessage(
                HttpMethod.Post,
                $"me/messages/{Uri.EscapeDataString(draftId)}/attachments/createUploadSession")
            {
                Content = JsonContent.Create(payload),
            },
            retrySafe: false,
            cancellationToken);
        if (sessionResponse.StatusCode != HttpStatusCode.Created)
        {
            throw GraphApiException.From(sessionResponse.StatusCode, sessionResponse.Headers.RetryAfter);
        }

        using var sessionDocument = await JsonDocument.ParseAsync(
            await sessionResponse.Content.ReadAsStreamAsync(cancellationToken),
            cancellationToken: cancellationToken);
        var uploadUrl = sessionDocument.RootElement.GetProperty("uploadUrl").GetString();
        if (!Uri.TryCreate(uploadUrl, UriKind.Absolute, out var uploadUri) || uploadUri.Scheme != Uri.UriSchemeHttps)
        {
            throw new GraphApiException(HttpStatusCode.BadGateway, null);
        }

        var buffer = new byte[UploadChunkSizeBytes];
        try
        {
            long offset = 0;
            while (offset < stream.Length)
            {
                var count = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken);
                if (count == 0)
                {
                    throw new EndOfStreamException("Attachment changed while uploading.");
                }

                using var response = await SendUploadChunkAsync(
                    uploadUri,
                    buffer,
                    count,
                    offset,
                    stream.Length,
                    cancellationToken);
                if (response.StatusCode is not HttpStatusCode.OK and
                    not HttpStatusCode.Created and
                    not HttpStatusCode.Accepted)
                {
                    throw GraphApiException.From(response.StatusCode, response.Headers.RetryAfter);
                }

                offset += count;
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(buffer);
        }
    }

    private async Task<HttpResponseMessage> SendUploadChunkAsync(
        Uri uploadUri,
        byte[] buffer,
        int count,
        long offset,
        long total,
        CancellationToken cancellationToken)
    {
        for (var retry = 0; ; retry++)
        {
            var content = new ByteArrayContent(buffer, 0, count);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            content.Headers.ContentRange = new ContentRangeHeaderValue(offset, offset + count - 1, total);
            using var request = new HttpRequestMessage(HttpMethod.Put, uploadUri) { Content = content };
            try
            {
                var response = await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
                if (!IsTransient(response.StatusCode) || retry >= options.MaximumRetryAttempts)
                {
                    return response;
                }

                var delay = GetRetryDelay(response.Headers.RetryAfter, retry);
                response.Dispose();
                await Task.Delay(delay, cancellationToken);
            }
            catch (HttpRequestException) when (retry < options.MaximumRetryAttempts)
            {
                await Task.Delay(GetRetryDelay(null, retry), cancellationToken);
            }
        }
    }

    private async Task<HttpResponseMessage> SendGraphAsync(
        Func<HttpRequestMessage> requestFactory,
        bool retrySafe,
        CancellationToken cancellationToken,
        bool requireSendPermission = false)
    {
        for (var retry = 0; ; retry++)
        {
            var token = await accountSession.GetAccessTokenAsync(requireSendPermission, cancellationToken);
            using var request = requestFactory();
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            request.Headers.TryAddWithoutValidation("Prefer", "IdType=\"ImmutableId\"");
            try
            {
                var response = await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
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

    private static void ValidateAttachmentBytes(
        DispatchAttachmentSnapshot attachment,
        byte[] bytes)
    {
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        if (bytes.LongLength != attachment.FileSizeBytes ||
            !string.Equals(hash, attachment.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException("Attachment changed before upload.");
        }
    }

    private static async Task ValidateAttachmentStreamAsync(
        DispatchAttachmentSnapshot attachment,
        FileStream stream,
        CancellationToken cancellationToken)
    {
        if (stream.Length != attachment.FileSizeBytes)
        {
            throw new IOException("Attachment changed before upload.");
        }

        var hash = Convert.ToHexString(await SHA256.HashDataAsync(stream, cancellationToken)).ToLowerInvariant();
        if (!string.Equals(hash, attachment.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException("Attachment changed before upload.");
        }

        stream.Position = 0;
    }

    private void ValidateEnvelope(EmailEnvelope envelope)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        if (!options.IsConfigured || accountSession.ProviderKey != ProviderKey)
        {
            throw new EmailAccountSessionException("GRAPH_NOT_CONFIGURED", "Microsoft Graph não está configurado.");
        }

        var recipients = envelope.To.Concat(envelope.Cc).ToArray();
        if (recipients.Length != 1 || envelope.To.Count != 1 || envelope.Cc.Count != 0 ||
            !string.Equals(recipients[0], options.ControlledRecipient.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            throw new EmailAccountSessionException(
                "GRAPH_RECIPIENT_NOT_CONTROLLED",
                "A Fase 7 permite somente o destinatário Microsoft controlado.");
        }

        if (envelope.Attachments.Sum(item => item.FileSizeBytes) > options.MaximumAttachmentBytes)
        {
            throw new IOException("Attachment limit exceeded.");
        }
    }

    private static Dictionary<string, object?> CreateMessagePayload(
        EmailEnvelope envelope,
        string operationKey) => new()
        {
            ["subject"] = envelope.Subject,
            ["body"] = new Dictionary<string, object?>
            {
                ["contentType"] = "HTML",
                ["content"] = envelope.HtmlBody,
            },
            ["toRecipients"] = envelope.To.Select(Address).ToArray(),
            ["ccRecipients"] = envelope.Cc.Select(Address).ToArray(),
            ["internetMessageHeaders"] = new[]
            {
                new Dictionary<string, object?>
                {
                    ["name"] = "x-folhas-idempotency",
                    ["value"] = operationKey,
                },
            },
            ["singleValueExtendedProperties"] = new[]
            {
                new Dictionary<string, object?>
                {
                    ["id"] = IdempotencyPropertyId,
                    ["value"] = operationKey,
                },
            },
        };

    private static Dictionary<string, object?> Address(string email) => new()
    {
        ["emailAddress"] = new Dictionary<string, string> { ["address"] = email },
    };

    private static string CreateOperationKey(EmailEnvelope envelope) =>
        $"folhas:{envelope.OperationMode}:{envelope.DispatchItemId:N}:{envelope.DispatchFingerprint}";

    private static string CreateOperationKey(DeliveryAttempt attempt) =>
        $"folhas:{attempt.Mode}:{attempt.DispatchItemId:N}:{attempt.DispatchFingerprint}";

    private static string CreateAttachmentContentId(string sha256) => $"folhas-{sha256.ToLowerInvariant()}";

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

    private static EmailProviderResult AuthenticationFailure(
        EmailAccountSessionException exception,
        string? draftId = null) => new(
        DeliveryAttemptState.FailedPermanent,
        null,
        draftId,
        exception.Code,
        exception.Message);

    private static EmailProviderResult MapFailure(
        GraphApiException exception,
        bool operationMayHaveCompleted,
        string? draftId = null)
    {
        if (exception.StatusCode is HttpStatusCode.Unauthorized)
        {
            return Permanent("AUTH_REVOKED", "A autorização Microsoft foi rejeitada ou revogada.", draftId);
        }

        if (exception.StatusCode is HttpStatusCode.Forbidden)
        {
            return Permanent("PROVIDER_REJECTED", "O Microsoft Graph recusou a permissão para esta operação.", draftId);
        }

        if (exception.StatusCode is HttpStatusCode.TooManyRequests)
        {
            var retry = exception.RetryAfter?.TotalSeconds.ToString("0", CultureInfo.InvariantCulture);
            return new EmailProviderResult(
                DeliveryAttemptState.FailedTransient,
                null,
                draftId,
                "PROVIDER_THROTTLED",
                retry is null ? "O Graph limitou a operação temporariamente." : $"O Graph solicitou aguardar {retry} segundo(s).");
        }

        if (exception.StatusCode is HttpStatusCode.BadRequest or
            HttpStatusCode.RequestEntityTooLarge or
            HttpStatusCode.UnsupportedMediaType or
            HttpStatusCode.UnprocessableEntity)
        {
            return Permanent("PROVIDER_REJECTED", "O Microsoft Graph rejeitou o conteúdo da operação.", draftId);
        }

        if (operationMayHaveCompleted)
        {
            return new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                draftId,
                "SEND_AMBIGUOUS",
                "O Graph não confirmou o resultado; reconciliação obrigatória.");
        }

        if (IsTransient(exception.StatusCode))
        {
            return new EmailProviderResult(
                DeliveryAttemptState.FailedTransient,
                null,
                draftId,
                "PROVIDER_TRANSIENT_FAILURE",
                "O Graph está temporariamente indisponível.");
        }

        return Permanent("PROVIDER_REJECTED", "O Microsoft Graph rejeitou a operação.", draftId);
    }

    private static EmailProviderResult Permanent(string code, string message, string? draftId = null) => new(
        DeliveryAttemptState.FailedPermanent,
        null,
        draftId,
        code,
        message);

    private static EmailProviderResult Ambiguous(string code) => new(
        DeliveryAttemptState.Ambiguous,
        null,
        null,
        code,
        "O resultado Microsoft permanece desconhecido; não tente reenviar sem reconciliação.");

    private sealed record GraphMessage(string Id, bool IsDraft);

    private sealed class GraphApiException(
        HttpStatusCode statusCode,
        TimeSpan? retryAfter) : Exception("Microsoft Graph request failed.")
    {
        public HttpStatusCode StatusCode { get; } = statusCode;

        public TimeSpan? RetryAfter { get; } = retryAfter;

        public static GraphApiException From(
            HttpStatusCode statusCode,
            RetryConditionHeaderValue? retryAfter) => new(
            statusCode,
            retryAfter?.Delta ?? (retryAfter?.Date is { } date ? date - DateTimeOffset.UtcNow : null));
    }
}
