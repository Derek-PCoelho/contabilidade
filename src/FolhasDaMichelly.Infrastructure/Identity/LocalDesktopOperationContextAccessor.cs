using System.Collections.Frozen;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Infrastructure.Identity;

public sealed class LocalDesktopOperationContextAccessor
    : IDocumentReviewContextAccessor, IDispatchExecutionContextAccessor
{
    public const string LocalScopeKey = "unauthenticated-local";
    public const string LocalActorId = "unauthenticated-operator";

    private static readonly FrozenSet<string> LocalPermissions = new[]
    {
        AppPermissions.DocumentsProcess,
        AppPermissions.BatchApprove,
        AppPermissions.EmailDraft,
        AppPermissions.AuditExport,
    }.ToFrozenSet(StringComparer.Ordinal);

    public Task<DocumentReviewContext> GetCurrentAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(new DocumentReviewContext(LocalScopeKey, LocalActorId));
    }

    Task<DispatchExecutionContext> IDispatchExecutionContextAccessor.GetCurrentAsync(
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(new DispatchExecutionContext(
            LocalScopeKey,
            LocalActorId,
            LocalPermissions));
    }
}
