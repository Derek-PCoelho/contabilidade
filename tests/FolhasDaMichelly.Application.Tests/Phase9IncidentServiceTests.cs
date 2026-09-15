using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Incidents;
using FolhasDaMichelly.Application.Security;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase9IncidentServiceTests
{
    [Fact]
    public async Task IncidentMustReferenceAttemptRedactsTextAndPreventsDuplicateActiveRecord()
    {
        var fixture = CreateFixture();
        var request = new OpenIncidentRequest(
            fixture.AttemptId,
            IncidentCategory.AmbiguousProviderResult,
            IncidentSeverity.High,
            "Possível resultado incerto para cliente@example.invalid; conferir antes de repetir.");

        var workspace = await fixture.Service.OpenAsync(request, CancellationToken.None);

        var incident = Assert.Single(workspace.Incidents);
        Assert.Equal(IncidentStatus.Open, incident.Status);
        Assert.DoesNotContain("cliente@example.invalid", incident.Summary, StringComparison.Ordinal);
        Assert.Single(workspace.AuditEvents);
        var duplicate = await Assert.ThrowsAsync<IncidentException>(() =>
            fixture.Service.OpenAsync(request, CancellationToken.None));
        Assert.Equal("INCIDENT_ALREADY_OPEN", duplicate.Code);
        var missing = await Assert.ThrowsAsync<IncidentException>(() => fixture.Service.OpenAsync(
            request with { DeliveryAttemptId = Guid.NewGuid(), Category = IncidentCategory.Other },
            CancellationToken.None));
        Assert.Equal("INCIDENT_ATTEMPT_NOT_FOUND", missing.Code);
    }

    [Fact]
    public async Task ResolutionRequiresEvidenceAndEveryTransitionAppendsAuditWithOptimisticVersion()
    {
        var fixture = CreateFixture();
        var opened = await fixture.Service.OpenAsync(
            new OpenIncidentRequest(
                fixture.AttemptId,
                IncidentCategory.PotentialWrongAttachment,
                IncidentSeverity.Critical,
                "Anexo potencialmente incorreto identificado durante a conferência final."),
            CancellationToken.None);
        var incident = Assert.Single(opened.Incidents);

        var shortNote = await Assert.ThrowsAsync<IncidentException>(() => fixture.Service.TransitionAsync(
            new TransitionIncidentRequest(incident.Id, incident.Version, IncidentStatus.Resolved, "feito"),
            CancellationToken.None));
        Assert.Equal("INCIDENT_NOTE_REQUIRED", shortNote.Code);

        var investigating = await fixture.Service.TransitionAsync(
            new TransitionIncidentRequest(incident.Id, 1, IncidentStatus.Investigating, "Conferindo os anexos."),
            CancellationToken.None);
        var changed = Assert.Single(investigating.Incidents);
        Assert.Equal(2, changed.Version);
        Assert.Equal(2, investigating.AuditEvents.Count);

        var conflict = await Assert.ThrowsAsync<IncidentException>(() => fixture.Service.TransitionAsync(
            new TransitionIncidentRequest(incident.Id, 1, IncidentStatus.Resolved, "Anexo correto confirmado."),
            CancellationToken.None));
        Assert.Equal("INCIDENT_VERSION_CONFLICT", conflict.Code);

        var resolved = await fixture.Service.TransitionAsync(
            new TransitionIncidentRequest(incident.Id, 2, IncidentStatus.Resolved, "Anexo correto confirmado no acervo protegido."),
            CancellationToken.None);
        Assert.Equal(IncidentStatus.Resolved, Assert.Single(resolved.Incidents).Status);
        Assert.Equal(3, resolved.AuditEvents.Count);
    }

    private static Fixture CreateFixture()
    {
        var attemptId = Guid.NewGuid();
        var attempt = new DeliveryAttempt(
            attemptId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            1,
            DispatchOperationMode.Test,
            DeliveryAttemptState.Ambiguous,
            DispatchWorkflowOptions.FakeProviderKey,
            "phase9:synthetic",
            new string('a', 64),
            null,
            null,
            "SYNTHETIC",
            "erro sintético",
            new DateTimeOffset(2026, 8, 21, 12, 0, 0, TimeSpan.Zero),
            null);
        var dispatch = new InMemoryDispatchStore(new DispatchWorkspace("scope-a", [], [], [attempt], []));
        var incidentStore = new InMemoryIncidentStore();
        var service = new IncidentService(
            incidentStore,
            dispatch,
            new FixedContextAccessor(),
            new TestRedactor(),
            new FixedClock());
        return new Fixture(attemptId, service);
    }

    private sealed record Fixture(Guid AttemptId, IncidentService Service);

    private sealed class InMemoryIncidentStore : IIncidentStore
    {
        private IncidentWorkspace workspace = IncidentWorkspace.Empty("scope-a");
        public Task<IncidentWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken) =>
            Task.FromResult(workspace);
        public Task SaveAsync(IncidentWorkspace value, CancellationToken cancellationToken)
        {
            workspace = value;
            return Task.CompletedTask;
        }
    }

    private sealed class InMemoryDispatchStore(DispatchWorkspace workspace) : IDispatchWorkflowStore
    {
        public Task<DispatchWorkspace> LoadAsync(string scopeKey, CancellationToken cancellationToken) =>
            Task.FromResult(workspace);
        public Task SaveAsync(DispatchWorkspace value, CancellationToken cancellationToken) => Task.CompletedTask;
    }

    private sealed class FixedContextAccessor : IDispatchExecutionContextAccessor
    {
        public Task<DispatchExecutionContext> GetCurrentAsync(CancellationToken cancellationToken) =>
            Task.FromResult(new DispatchExecutionContext("scope-a", "operator-a", new HashSet<string>()));
    }

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow { get; } = new(2026, 8, 21, 12, 30, 0, TimeSpan.Zero);
    }

    private sealed class TestRedactor : ISensitiveTextRedactor
    {
        public string Redact(string? value, int maximumLength = 2_000) =>
            (value ?? string.Empty).Replace("cliente@example.invalid", "c***@example.invalid", StringComparison.Ordinal);
    }
}
