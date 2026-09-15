using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Infrastructure.Dispatch;
using FolhasDaMichelly.Infrastructure.Security;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase7MicrosoftGraphLiveTests
{
    [Fact]
    [Trait("Category", "MicrosoftGraphLive")]
    public async Task DedicatedAccountCreatesDraftAndOptionallySendsOnlyToControlledRecipient()
    {
        if (!string.Equals(
                Environment.GetEnvironmentVariable("FOLHAS_GRAPH_LIVE_TEST"),
                "true",
                StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        var clientId = Environment.GetEnvironmentVariable("FOLHAS_GRAPH_CLIENT_ID") ?? string.Empty;
        var controlledRecipient = Environment.GetEnvironmentVariable("FOLHAS_GRAPH_CONTROLLED_RECIPIENT") ?? string.Empty;
        var sendEnabled = string.Equals(
            Environment.GetEnvironmentVariable("FOLHAS_GRAPH_LIVE_SEND"),
            "true",
            StringComparison.OrdinalIgnoreCase);
        var options = new MicrosoftGraphOptions
        {
            Enabled = true,
            EmailSendEnabled = sendEnabled,
            ClientId = clientId,
            TenantId = Environment.GetEnvironmentVariable("FOLHAS_GRAPH_TENANT_ID") ?? "organizations",
            ControlledRecipient = controlledRecipient,
        };
        Assert.True(options.IsConfigured, "ClientId e destinatário controlado são obrigatórios para o teste live.");

        var dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "FolhasDaMichelly");
        using var session = new MicrosoftGraphEmailAccountSession(
            options,
            new NativeSecretStore(dataDirectory));
        var status = await session.GetStatusAsync(CancellationToken.None);
        Assert.True(status.IsConnected, "Conecte a conta pelo Desktop antes do teste live.");

        using var httpClient = new HttpClient { BaseAddress = options.ApiBaseAddress };
        var provider = new MicrosoftGraphEmailProvider(httpClient, session, options);
        var temporaryPath = Path.Combine(Path.GetTempPath(), $"folhas-graph-live-{Guid.NewGuid():N}.pdf");
        await File.WriteAllTextAsync(
            temporaryPath,
            "%PDF-1.4\nDADOS SINTÉTICOS - SEM VALIDADE\n%%EOF",
            Encoding.ASCII);
        string? draftId = null;
        try
        {
            var bytes = await File.ReadAllBytesAsync(temporaryPath);
            var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var envelope = new EmailEnvelope(
                Guid.NewGuid(),
                $"phase7:live:{Guid.NewGuid():N}:attempt:1",
                DispatchOperationMode.Send,
                hash,
                "microsoft-graph://me",
                [controlledRecipient],
                [],
                "[FASE 7 — TESTE CONTROLADO] Dados sintéticos sem validade",
                "Mensagem sintética do gate opt-in da Fase 7.",
                "<div>Mensagem sintética do gate opt-in da Fase 7.</div>",
                [new DispatchAttachmentSnapshot(
                    Guid.NewGuid(),
                    temporaryPath,
                    Path.GetFileName(temporaryPath),
                    hash,
                    bytes.Length,
                    RecognizedDocumentType.Payroll)],
                FakeDeliveryScenario.Success);

            var draft = await provider.CreateDraftAsync(envelope, CancellationToken.None);
            Assert.Equal(DeliveryAttemptState.DraftCreated, draft.State);
            draftId = draft.ProviderDraftId;
            Assert.False(string.IsNullOrWhiteSpace(draftId));

            if (sendEnabled)
            {
                var sent = await provider.SendAsync(envelope, CancellationToken.None);
                Assert.Equal(DeliveryAttemptState.AcceptedByProvider, sent.State);
                draftId = null;
            }
        }
        finally
        {
            File.Delete(temporaryPath);
            if (!string.IsNullOrWhiteSpace(draftId))
            {
                var token = await session.GetAccessTokenAsync(requireSendPermission: false, CancellationToken.None);
                using var request = new HttpRequestMessage(
                    HttpMethod.Delete,
                    $"me/messages/{Uri.EscapeDataString(draftId)}");
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                request.Headers.TryAddWithoutValidation("Prefer", "IdType=\"ImmutableId\"");
                using var response = await httpClient.SendAsync(request);
                Assert.True(response.IsSuccessStatusCode, "O rascunho sintético live não pôde ser removido.");
            }
        }
    }
}
