using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using Microsoft.Identity.Client;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class MicrosoftGraphEmailAccountSession : IEmailAccountSession, IDisposable
{
    private const string TokenCacheKey = "email-provider/microsoft-graph/msal-v3-cache";
    private readonly MicrosoftGraphOptions options;
    private readonly ISecretStore secretStore;
    private readonly SemaphoreSlim cacheGate = new(1, 1);
    private readonly Lazy<IPublicClientApplication>? application;

    public MicrosoftGraphEmailAccountSession(
        MicrosoftGraphOptions options,
        ISecretStore secretStore)
    {
        this.options = options;
        this.secretStore = secretStore;
        if (options.IsConfigured)
        {
            application = new Lazy<IPublicClientApplication>(CreateApplication);
        }
    }

    public string ProviderKey => DispatchWorkflowOptions.MicrosoftGraphProviderKey;

    public async Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken)
    {
        if (!options.IsConfigured || application is null)
        {
            return NotConfigured();
        }

        var account = (await application.Value.GetAccountsAsync()).FirstOrDefault();
        return account is null
            ? new EmailAccountConnectionStatus(ProviderKey, true, false, null, "Microsoft 365 desconectado", "AUTH_REQUIRED")
            : Connected(account);
    }

    public async Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken)
    {
        if (!options.IsConfigured || application is null)
        {
            return NotConfigured();
        }

        var client = GetApplication();
        try
        {
            var result = await client.AcquireTokenInteractive(
                    options.EmailSendEnabled ? options.SendScopes : options.DraftScopes)
                .WithUseEmbeddedWebView(false)
                .ExecuteAsync(cancellationToken);
            return Connected(result.Account);
        }
        catch (MsalClientException exception) when (exception.ErrorCode == "authentication_canceled")
        {
            return new EmailAccountConnectionStatus(
                ProviderKey,
                true,
                false,
                null,
                "Conexão Microsoft cancelada",
                "AUTH_CANCELLED");
        }
        catch (MsalException)
        {
            return new EmailAccountConnectionStatus(
                ProviderKey,
                true,
                false,
                null,
                "Não foi possível conectar a conta Microsoft",
                "AUTH_REQUIRED");
        }
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken)
    {
        if (application is not null)
        {
            foreach (var account in await application.Value.GetAccountsAsync())
            {
                cancellationToken.ThrowIfCancellationRequested();
                await application.Value.RemoveAsync(account);
            }
        }

        await secretStore.RemoveAsync(TokenCacheKey, cancellationToken);
    }

    public async Task<string> GetAccessTokenAsync(
        bool requireSendPermission,
        CancellationToken cancellationToken)
    {
        var client = GetApplication();
        var account = (await client.GetAccountsAsync()).FirstOrDefault()
            ?? throw new EmailAccountSessionException("AUTH_REQUIRED", "Conecte uma conta Microsoft 365 de teste.");
        try
        {
            var result = await client.AcquireTokenSilent(
                    requireSendPermission ? options.SendScopes : options.DraftScopes,
                    account)
                .ExecuteAsync(cancellationToken);
            return result.AccessToken;
        }
        catch (MsalUiRequiredException exception)
        {
            throw new EmailAccountSessionException(
                "AUTH_REVOKED",
                "A autorização Microsoft expirou ou foi revogada; conecte novamente.",
                exception);
        }
        catch (MsalException exception)
        {
            throw new EmailAccountSessionException(
                "AUTH_REQUIRED",
                "Não foi possível obter autorização Microsoft silenciosamente.",
                exception);
        }
    }

    private IPublicClientApplication CreateApplication()
    {
        var client = PublicClientApplicationBuilder
            .Create(options.ClientId)
            .WithAuthority($"https://login.microsoftonline.com/{Uri.EscapeDataString(options.TenantId)}")
            .WithRedirectUri(options.RedirectUri)
            .Build();
        client.UserTokenCache.SetBeforeAccessAsync(BeforeCacheAccessAsync);
        client.UserTokenCache.SetAfterAccessAsync(AfterCacheAccessAsync);
        return client;
    }

    private async Task BeforeCacheAccessAsync(TokenCacheNotificationArgs args)
    {
        await cacheGate.WaitAsync().ConfigureAwait(false);
        try
        {
            var serialized = await secretStore.RetrieveAsync(TokenCacheKey, CancellationToken.None).ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(serialized))
            {
                args.TokenCache.DeserializeMsalV3(Convert.FromBase64String(serialized), shouldClearExistingCache: true);
            }
        }
        finally
        {
            cacheGate.Release();
        }
    }

    private async Task AfterCacheAccessAsync(TokenCacheNotificationArgs args)
    {
        if (!args.HasStateChanged)
        {
            return;
        }

        await cacheGate.WaitAsync().ConfigureAwait(false);
        try
        {
            var bytes = args.TokenCache.SerializeMsalV3();
            try
            {
                await secretStore.StoreAsync(
                    TokenCacheKey,
                    Convert.ToBase64String(bytes),
                    CancellationToken.None).ConfigureAwait(false);
            }
            finally
            {
                System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes);
            }
        }
        finally
        {
            cacheGate.Release();
        }
    }

    private IPublicClientApplication GetApplication() => application?.Value
        ?? throw new EmailAccountSessionException(
            "GRAPH_NOT_CONFIGURED",
            "Configure ClientId, tenant e destinatário controlado para a Fase 7.");

    private EmailAccountConnectionStatus NotConfigured() => new(
        ProviderKey,
        false,
        false,
        null,
        "Microsoft Graph não configurado",
        "GRAPH_NOT_CONFIGURED");

    private EmailAccountConnectionStatus Connected(IAccount account) => new(
        ProviderKey,
        true,
        true,
        account.Username,
        string.IsNullOrWhiteSpace(account.Username) ? "Conta Microsoft 365 conectada" : account.Username,
        null);

    public void Dispose()
    {
        cacheGate.Dispose();
        GC.SuppressFinalize(this);
    }
}
