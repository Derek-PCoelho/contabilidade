using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Application.Dispatch;

public sealed class EmailAccountConnectionService(
    IEmailAccountSession session,
    IDispatchWorkflowStore store,
    IDispatchExecutionContextAccessor contextAccessor,
    IClock clock) : IEmailAccountConnectionService
{
    public Task<EmailAccountConnectionStatus> GetStatusAsync(CancellationToken cancellationToken) =>
        session.GetStatusAsync(cancellationToken);

    public async Task<EmailAccountConnectionStatus> ConnectAsync(CancellationToken cancellationToken)
    {
        var status = await session.ConnectAsync(cancellationToken);
        await AuditAsync("email_provider_connected", status.IsConnected ? "connected" : "failed", status.ErrorCode, cancellationToken);
        return status;
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken)
    {
        await session.DisconnectAsync(cancellationToken);
        await AuditAsync("email_provider_disconnected", "disconnected", null, cancellationToken);
    }

    private async Task AuditAsync(
        string action,
        string outcome,
        string? errorCode,
        CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var audit = new DispatchAuditEvent(
            Guid.NewGuid(),
            context.ScopeKey,
            context.ActorId,
            clock.UtcNow,
            action,
            null,
            null,
            null,
            outcome,
            errorCode,
            Guid.NewGuid().ToString("N"));
        await store.SaveAsync(
            workspace with { AuditEvents = [.. workspace.AuditEvents, audit] },
            cancellationToken);
    }
}
