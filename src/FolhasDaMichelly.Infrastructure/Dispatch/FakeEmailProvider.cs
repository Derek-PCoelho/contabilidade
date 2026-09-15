using System.Text.Json;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class FakeEmailProviderOptions
{
    public string OutputDirectory { get; set; } = Path.Combine(Path.GetTempPath(), "folhas-da-michelly-fake-outbox");

    public int SimulatedLatencyMilliseconds { get; set; } = 75;
}

public sealed class FakeEmailProvider(FakeEmailProviderOptions options) : IEmailProvider
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
    };

    public string ProviderKey => DispatchWorkflowOptions.FakeProviderKey;

    public Task<EmailProviderAccount> GetAccountAsync(CancellationToken cancellationToken) =>
        Task.FromResult(new EmailProviderAccount(ProviderKey, "fake://local", "Caixa local simulada", true));

    public Task<EmailProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken) =>
        Task.FromResult(new EmailProviderCapabilities(true, true, true, 25L * 1024L * 1024L));

    public Task<EmailProviderResult> CreateDraftAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken) =>
        ExecuteAsync(envelope, isDraft: true, cancellationToken);

    public Task<EmailProviderResult> SendAsync(
        EmailEnvelope envelope,
        CancellationToken cancellationToken) =>
        ExecuteAsync(envelope, isDraft: false, cancellationToken);

    public async Task<EmailProviderResult> ReconcileAsync(
        DeliveryAttempt attempt,
        CancellationToken cancellationToken)
    {
        await DelayAsync(cancellationToken);
        var path = GetReceiptPath(attempt.IdempotencyKey);
        if (!File.Exists(path))
        {
            return new EmailProviderResult(
                DeliveryAttemptState.FailedPermanent,
                null,
                null,
                "FAKE_RECEIPT_NOT_FOUND",
                "Nenhum recibo local foi localizado para a chave informada.");
        }

        await using var stream = File.OpenRead(path);
        var receipt = await JsonSerializer.DeserializeAsync<FakeProviderReceipt>(
            stream,
            SerializerOptions,
            cancellationToken) ?? throw new InvalidOperationException("Invalid fake provider receipt.");
        return receipt.Result;
    }

    private async Task<EmailProviderResult> ExecuteAsync(
        EmailEnvelope envelope,
        bool isDraft,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        Directory.CreateDirectory(options.OutputDirectory);
        await DelayAsync(cancellationToken);
        var receiptPath = GetReceiptPath(envelope.IdempotencyKey);
        if (File.Exists(receiptPath))
        {
            await using var existingStream = File.OpenRead(receiptPath);
            var existing = await JsonSerializer.DeserializeAsync<FakeProviderReceipt>(
                existingStream,
                SerializerOptions,
                cancellationToken) ?? throw new InvalidOperationException("Invalid fake provider receipt.");
            return existing.Result;
        }

        var suffix = StableSuffix(envelope.IdempotencyKey);
        var successful = isDraft
            ? new EmailProviderResult(DeliveryAttemptState.DraftCreated, null, $"fake-draft-{suffix}", null, null)
            : new EmailProviderResult(DeliveryAttemptState.AcceptedByProvider, $"fake-message-{suffix}", null, null, null);
        var returned = envelope.Scenario switch
        {
            FakeDeliveryScenario.Success => successful,
            FakeDeliveryScenario.TransientFailure => new EmailProviderResult(
                DeliveryAttemptState.FailedTransient,
                null,
                null,
                "FAKE_TRANSIENT_FAILURE",
                "Falha transitória simulada pelo provedor local."),
            FakeDeliveryScenario.PermanentFailure => new EmailProviderResult(
                DeliveryAttemptState.FailedPermanent,
                null,
                null,
                "FAKE_PERMANENT_FAILURE",
                "Falha permanente simulada pelo provedor local."),
            FakeDeliveryScenario.Timeout => new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                null,
                "FAKE_TIMEOUT_AFTER_ACCEPTANCE",
                "Timeout simulado após aceite local; reconciliação obrigatória."),
            FakeDeliveryScenario.Ambiguous => new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                null,
                "FAKE_AMBIGUOUS_RESULT",
                "Resultado ambíguo simulado; nova tentativa cega é proibida."),
            _ => throw new ArgumentOutOfRangeException(nameof(envelope)),
        };
        var persisted = envelope.Scenario == FakeDeliveryScenario.Timeout ? successful : returned;
        var receipt = new FakeProviderReceipt(
            envelope.IdempotencyKey,
            envelope.DispatchItemId,
            envelope.SenderAccountId,
            envelope.To,
            envelope.Cc,
            envelope.Subject,
            envelope.TextBody,
            envelope.HtmlBody,
            envelope.Attachments.Select(attachment => new FakeAttachment(
                attachment.FileName,
                attachment.Sha256,
                attachment.FileSizeBytes)).ToArray(),
            persisted,
            DateTimeOffset.UtcNow);
        await WriteAtomicallyAsync(receiptPath, receipt, cancellationToken);
        return returned;
    }

    private async Task DelayAsync(CancellationToken cancellationToken)
    {
        if (options.SimulatedLatencyMilliseconds > 0)
        {
            await Task.Delay(options.SimulatedLatencyMilliseconds, cancellationToken);
        }
    }

    private string GetReceiptPath(string idempotencyKey) =>
        Path.Combine(options.OutputDirectory, $"{StableSuffix(idempotencyKey)}.json");

    private static string StableSuffix(string idempotencyKey)
    {
        var bytes = System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(idempotencyKey));
        return Convert.ToHexString(bytes)[..24].ToLowerInvariant();
    }

    private static async Task WriteAtomicallyAsync(
        string path,
        FakeProviderReceipt receipt,
        CancellationToken cancellationToken)
    {
        var temporaryPath = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            await using (var stream = new FileStream(
                temporaryPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                81920,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, receipt, SerializerOptions, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }

            File.Move(temporaryPath, path, overwrite: false);
        }
        catch (IOException) when (File.Exists(path))
        {
            File.Delete(temporaryPath);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private sealed record FakeAttachment(string FileName, string Sha256, long FileSizeBytes);

    private sealed record FakeProviderReceipt(
        string IdempotencyKey,
        Guid DispatchItemId,
        string SenderAccountId,
        IReadOnlyList<string> To,
        IReadOnlyList<string> Cc,
        string Subject,
        string TextBody,
        string HtmlBody,
        IReadOnlyList<FakeAttachment> Attachments,
        EmailProviderResult Result,
        DateTimeOffset CreatedAtUtc);
}
