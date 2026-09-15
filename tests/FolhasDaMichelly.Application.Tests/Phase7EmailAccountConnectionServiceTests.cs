using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase7EmailAccountConnectionServiceTests
{
    [Fact]
    public async Task ConnectAndDisconnectAreSeparateFromAppLoginAndAuditedLocally()
    {
        var session = new SyntheticEmailSession();
        var store = new InMemoryStore();
        var service = new EmailAccountConnectionService(
            session,
            store,
            new FixedContext(),
            new FixedClock());

        var connected = await service.ConnectAsync(CancellationToken.None);
        await service.DisconnectAsync(CancellationToken.None);

        Assert.True(connected.IsConnected);
        Assert.Equal(1, session.ConnectCount);
        Assert.Equal(1, session.DisconnectCount);
        Assert.Collection(
            store.Workspace.AuditEvents,
            audit =>
            {
                Assert.Equal("email_provider_connected", audit.Action);
                Assert.Equal("synthetic-operator", audit.ActorId);
            },
            audit => Assert.Equal("email_provider_disconnected", audit.Action));
    }

    private sealed class SyntheticEmailSession : IEmailAccountSession
    {
        public string ProviderKey => DispatchWorkflowOptions.MicrosoftGraphProviderKey;
        public int ConnectCount { get; private set; }
        public int DisconnectCount { get; private set; }

        public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
            Task.FromResult(Status(ConnectCount > DisconnectCount));

        public Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken)
        {
            ConnectCount++;
            return Task.FromResult(Status(true));
        }

        public Task DisconnectAsync(CancellationToken cancellationToken)
        {
            DisconnectCount++;
            return Task.CompletedTask;
        }

        public Task<string> GetAccessTokenAsync(bool requireSendPermission, CancellationToken cancellationToken) =>
            Task.FromResult("synthetic-token");

        private EmailAccountConnectionStatus Status(bool connected) => new(
            ProviderKey,
            true,
            connected,
            connected ? "microsoft-test@example.invalid" : null,
            connected ? "Microsoft Test" : "Desconectado",
            connected ? null : "AUTH_REQUIRED");
    }

    private sealed class InMemoryStore : IDispatchWorkflowStore
    {
        public DispatchWorkspace Workspace { get; private set; } = DispatchWorkspace.Empty("synthetic-scope");

        public Task<DispatchWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken) =>
            Task.FromResult(Workspace);

        public Task SaveAsync(DispatchWorkspace workspace, CancellationToken cancellationToken)
        {
            Workspace = workspace;
            return Task.CompletedTask;
        }
    }

    private sealed class FixedContext : IDispatchExecutionContextAccessor
    {
        public Task<DispatchExecutionContext> GetCurrentAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new DispatchExecutionContext(
                "synthetic-scope",
                "synthetic-operator",
                new HashSet<string>(StringComparer.Ordinal)));
    }

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow => new(2026, 8, 21, 18, 0, 0, TimeSpan.Zero);
    }
}
