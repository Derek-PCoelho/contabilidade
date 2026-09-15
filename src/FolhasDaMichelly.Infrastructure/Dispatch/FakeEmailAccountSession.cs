using FolhasDaMichelly.Application.Dispatch;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class FakeEmailAccountSession : IEmailAccountSession
{
    public string ProviderKey => DispatchWorkflowOptions.FakeProviderKey;

    public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
        Task.FromResult(new EmailAccountConnectionStatus(
            ProviderKey,
            true,
            true,
            "fake://local",
            "Modo seguro local",
            null));

    public Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken) =>
        GetStatusAsync(cancellationToken);

    public Task DisconnectAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    public Task<string> GetAccessTokenAsync(
        bool requireSendPermission,
        CancellationToken cancellationToken) => Task.FromResult(string.Empty);
}
