using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Pilot;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase11PilotReadinessTests
{
    [Fact]
    public async Task PilotBecomesReadyOnlyAfterEveryControlAndSafeMetricPasses()
    {
        var store = new InMemoryPilotChecklistStore();
        var service = CreateService(store);
        var incomplete = await service.LoadAsync(PilotOperationalMetrics.Empty, CancellationToken.None);

        Assert.False(incomplete.IsReady);
        Assert.Equal(0, incomplete.ConfirmedChecklistCount);
        Assert.Contains(incomplete.Blockers, blocker => blocker.Contains('6'));

        var ready = await service.UpdateAsync(
            new PilotChecklistUpdate(true, true, true, true, true, true),
            PilotOperationalMetrics.Empty,
            CancellationToken.None);

        Assert.True(ready.IsReady);
        Assert.Equal(6, ready.ConfirmedChecklistCount);
        Assert.Equal(6, store.Workspace.AuditEvents.Count);
        Assert.All(store.Workspace.AuditEvents, audit =>
            Assert.Equal("synthetic-operator", audit.ActorId));

        var unchanged = await service.UpdateAsync(
            new PilotChecklistUpdate(true, true, true, true, true, true),
            PilotOperationalMetrics.Empty,
            CancellationToken.None);
        Assert.True(unchanged.IsReady);
        Assert.Equal(6, store.Workspace.AuditEvents.Count);
    }

    [Fact]
    public async Task SendAttemptAmbiguityAndHighRiskIncidentStopPilot()
    {
        var service = CreateService(new InMemoryPilotChecklistStore());
        _ = await service.UpdateAsync(
            new PilotChecklistUpdate(true, true, true, true, true, true),
            PilotOperationalMetrics.Empty,
            CancellationToken.None);
        var stopped = await service.LoadAsync(
            PilotOperationalMetrics.Empty with
            {
                SendAttemptCount = 1,
                FailedOrAmbiguousAttemptCount = 1,
                OpenHighOrCriticalIncidentCount = 1,
            },
            CancellationToken.None);

        Assert.False(stopped.IsReady);
        Assert.Contains(stopped.Blockers, blocker => blocker.Contains("tentativa de envio", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(stopped.Blockers, blocker => blocker.Contains("resultado incerto", StringComparison.OrdinalIgnoreCase));
        Assert.Contains(stopped.Blockers, blocker => blocker.Contains("alta ou crítica", StringComparison.OrdinalIgnoreCase));
    }

    private static PilotReadinessService CreateService(IPilotChecklistStore store) => new(
        store,
        new FixedDispatchContext(),
        new FixedClock(),
        new PilotModeOptions
        {
            Enabled = true,
            EnvironmentName = "staging",
            AllowTest = true,
            AllowDraft = true,
            AllowSend = false,
            RequireNonProductionData = true,
            MaximumClients = 5,
        });

    private sealed class InMemoryPilotChecklistStore : IPilotChecklistStore
    {
        public PilotChecklistWorkspace Workspace { get; private set; } =
            PilotChecklistWorkspace.Empty("synthetic-scope");

        public Task<PilotChecklistWorkspace> LoadAsync(
            string scopeKey,
            CancellationToken cancellationToken) => Task.FromResult(Workspace);

        public Task SaveAsync(PilotChecklistWorkspace workspace, CancellationToken cancellationToken)
        {
            Workspace = workspace;
            return Task.CompletedTask;
        }
    }

    private sealed class FixedDispatchContext : IDispatchExecutionContextAccessor
    {
        public Task<DispatchExecutionContext> GetCurrentAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new DispatchExecutionContext(
                "synthetic-scope",
                "synthetic-operator",
                new HashSet<string>(StringComparer.Ordinal)));
    }

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow { get; } = new(2026, 8, 21, 18, 0, 0, TimeSpan.Zero);
    }
}
