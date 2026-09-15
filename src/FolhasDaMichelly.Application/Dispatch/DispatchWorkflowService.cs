using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Dispatch;

public sealed class DispatchWorkflowService(
    IDispatchWorkflowStore store,
    IDocumentReviewService reviewService,
    IDispatchMessageComposer composer,
    IEmailProvider emailProvider,
    IDispatchExecutionContextAccessor contextAccessor,
    IRemoteEmailSendGuard remoteSendGuard,
    IDispatchReportExporter reportExporter,
    IClock clock,
    DispatchWorkflowOptions options) : IDispatchWorkflowService
{
    public async Task<DispatchWorkspace> LoadAsync(CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var changed = false;
        foreach (var item in workspace.Items.Where(item =>
                     item.Approval is not null &&
                     item.State is DispatchItemState.Approved or DispatchItemState.Failed).ToArray())
        {
            var (currentItem, _) = await RevalidateAsync(workspace, item, context, cancellationToken);
            if (!Equals(currentItem, item))
            {
                workspace = ReplaceItem(workspace, currentItem, context, "dispatch_approval_invalidated");
                workspace = UpdateBatchState(workspace, item.BatchId);
                changed = true;
            }
        }

        if (changed)
        {
            await store.SaveAsync(workspace, cancellationToken);
        }

        return workspace;
    }

    public async Task<DispatchWorkspace> PrepareAsync(
        PrepareDispatchRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        ValidatePilotMode(request.OperationMode);
        ValidateProductionMode(request.OperationMode, request.GroupIds.Distinct().Count());
        var groupIds = request.GroupIds.Distinct().ToArray();
        if (groupIds.Length == 0 ||
            request.SelectionMode == ProcessingSelectionMode.Individual && groupIds.Length != 1)
        {
            throw Error("DISPATCH_SELECTION_INVALID", "Selecione um conjunto para um cliente ou vários conjuntos para preparar em sequência.");
        }

        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        Demand(context, AppPermissions.DocumentsProcess, "DISPATCH_PREPARE_FORBIDDEN");
        var review = await reviewService.LoadAsync(cancellationToken);
        var selectedGroups = groupIds.Select(id =>
                review.Groups.SingleOrDefault(group => group.Id == id)
                ?? throw Error("REVIEW_GROUP_NOT_FOUND", $"Conjunto {id:D} não encontrado."))
            .ToArray();
        if (request.SelectionMode == ProcessingSelectionMode.Batch &&
            selectedGroups
                .Select(group => DispatchCompetenceKey(group, review))
                .Distinct(StringComparer.Ordinal)
                .Count() > 1)
        {
            throw Error(
                "DISPATCH_PERIOD_MISMATCH",
                "Uma sequência de mensagens deve conter conjuntos de uma única competência mensal.");
        }

        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        if (workspace.Attempts.Any(attempt =>
            groupIds.Contains(attempt.GroupId) &&
            attempt.State is DeliveryAttemptState.Pending or DeliveryAttemptState.Ambiguous))
        {
            throw Error(
                "DISPATCH_RECONCILIATION_REQUIRED",
                "Há resultado pendente ou incerto neste conjunto; confira a situação antes de preparar outra mensagem.");
        }

        var batches = workspace.Batches.ToList();
        var items = workspace.Items.Select(item =>
            groupIds.Contains(item.GroupId) && !IsTerminal(item)
                ? item with { State = DispatchItemState.Cancelled, Approval = null, UpdatedAtUtc = clock.UtcNow }
                : item).ToList();
        var audits = workspace.AuditEvents.ToList();
        var batchId = Guid.NewGuid();
        var newItems = new List<DispatchItem>();
        foreach (var group in selectedGroups)
        {
            var documents = review.Documents.Where(document => group.DocumentIds.Contains(document.Id)).ToArray();
            var (message, compositionBlocks) = await composer.ComposeAsync(
                group,
                documents,
                request.OperationMode,
                request.TestDestination,
                cancellationToken);
            var blocks = compositionBlocks.ToList();
            if (!group.IsApproved || group.ApprovalSnapshot is null)
            {
                blocks.Add(new DispatchBlock(
                    "REVIEW_GROUP_NOT_APPROVED",
                    ValidationSeverity.Blocker,
                    "O conjunto de documentos precisa estar liberado antes de preparar a mensagem."));
            }

            var itemId = Guid.NewGuid();
            var item = new DispatchItem(
                itemId,
                batchId,
                group.Id,
                group.ClientId,
                group.ClientDisplayName,
                group.EstablishmentId,
                group.PeriodLabel,
                request.OperationMode,
                request.Scenario,
                request.TestDestination?.Trim().ToLowerInvariant(),
                blocks.Any(block => block.Severity is ValidationSeverity.Error or ValidationSeverity.Blocker)
                    ? DispatchItemState.Blocked
                    : DispatchItemState.ReadyForApproval,
                NextDispatchRevision(workspace, group.Id),
                message,
                null,
                blocks,
                clock.UtcNow,
                clock.UtcNow);
            newItems.Add(item);
            audits.Add(Audit(context, "dispatch_composed", batchId, itemId, group.Id, item.State.ToString()));
        }

        items.AddRange(newItems);
        batches.Add(new ProcessingBatch(
            batchId,
            context.ScopeKey,
            request.SelectionMode,
            request.OperationMode,
            newItems.All(item => item.State == DispatchItemState.ReadyForApproval)
                ? ProcessingBatchState.ReadyForReview
                : ProcessingBatchState.Preparing,
            groupIds,
            newItems.Select(item => item.Id).ToArray(),
            context.ActorId,
            clock.UtcNow,
            clock.UtcNow));
        var updated = workspace with { Batches = batches, Items = items, AuditEvents = audits };
        await store.SaveAsync(updated, cancellationToken);
        return updated;
    }

    public async Task<DispatchWorkspace> ApproveAsync(
        Guid dispatchItemId,
        CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        Demand(context, AppPermissions.DocumentsProcess, "DISPATCH_APPROVE_FORBIDDEN");
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var item = FindItem(workspace, dispatchItemId);
        var batch = workspace.Batches.Single(current => current.Id == item.BatchId);
        if (batch.SelectionMode == ProcessingSelectionMode.Batch)
        {
            Demand(context, AppPermissions.BatchApprove, "BATCH_APPROVE_FORBIDDEN");
        }

        var (currentItem, review) = await RevalidateAsync(workspace, item, context, cancellationToken);
        if (!Equals(currentItem, item))
        {
            var invalidated = ReplaceItem(workspace, currentItem, context, "dispatch_approval_invalidated");
            await store.SaveAsync(invalidated, cancellationToken);
            throw Error("DISPATCH_CHANGED_AFTER_REVIEW", "Destinatários, template ou documentos mudaram; revise e aprove novamente.");
        }

        if (item.State != DispatchItemState.ReadyForApproval || item.PreventsApproval || item.Message is null)
        {
            throw Error("DISPATCH_NOT_APPROVABLE", "O item contém bloqueios ou não está pronto para aprovação.");
        }

        var group = review.Groups.Single(group => group.Id == item.GroupId);
        var approved = item with
        {
            State = DispatchItemState.Approved,
            Approval = new DispatchApprovalSnapshot(
                Guid.NewGuid(),
                item.Id,
                item.Revision,
                group.ApprovalSnapshot!.Id,
                group.ApprovalSnapshot.ContentHash,
                item.Message.DispatchFingerprint,
                context.ActorId,
                clock.UtcNow),
            UpdatedAtUtc = clock.UtcNow,
        };
        var updated = ReplaceItem(workspace, approved, context, "dispatch_approved");
        updated = UpdateBatchState(updated, item.BatchId);
        await store.SaveAsync(updated, cancellationToken);
        return updated;
    }

    public async Task<DispatchWorkspace> ApproveBatchAsync(
        Guid batchId,
        CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        Demand(context, AppPermissions.DocumentsProcess, "DISPATCH_APPROVE_FORBIDDEN");
        Demand(context, AppPermissions.BatchApprove, "BATCH_APPROVE_FORBIDDEN");
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var batch = workspace.Batches.SingleOrDefault(current => current.Id == batchId)
            ?? throw Error("DISPATCH_BATCH_NOT_FOUND", "Sequência de mensagens não encontrada.");
        if (batch.SelectionMode != ProcessingSelectionMode.Batch)
        {
            throw Error("DISPATCH_BATCH_MODE_REQUIRED", "A aprovação conjunta exige mensagens preparadas na mesma sequência.");
        }

        var candidates = workspace.Items
            .Where(item => batch.DispatchItemIds.Contains(item.Id) &&
                item.State == DispatchItemState.ReadyForApproval &&
                !item.PreventsApproval &&
                item.Message is not null)
            .ToArray();
        if (candidates.Length == 0)
        {
            throw Error("DISPATCH_BATCH_EMPTY", "Nenhuma mensagem elegível está pronta; itens bloqueados ficam fora da aprovação conjunta.");
        }

        foreach (var item in candidates)
        {
            var (currentItem, review) = await RevalidateAsync(workspace, item, context, cancellationToken);
            if (!Equals(currentItem, item))
            {
                workspace = ReplaceItem(workspace, currentItem, context, "dispatch_approval_invalidated");
                continue;
            }

            var group = review.Groups.Single(group => group.Id == item.GroupId);
            var approved = item with
            {
                State = DispatchItemState.Approved,
                Approval = new DispatchApprovalSnapshot(
                    Guid.NewGuid(),
                    item.Id,
                    item.Revision,
                    group.ApprovalSnapshot!.Id,
                    group.ApprovalSnapshot.ContentHash,
                    item.Message!.DispatchFingerprint,
                    context.ActorId,
                    clock.UtcNow),
                UpdatedAtUtc = clock.UtcNow,
            };
            workspace = ReplaceItem(workspace, approved, context, "dispatch_bulk_approved");
        }

        workspace = UpdateBatchState(workspace, batchId);
        await store.SaveAsync(workspace, cancellationToken);
        return workspace;
    }

    public async Task<DispatchWorkspace> ExecuteAsync(
        ExecuteDispatchRequest request,
        CancellationToken cancellationToken)
        => await ExecuteCoreAsync(request, 1, cancellationToken);

    private async Task<DispatchWorkspace> ExecuteCoreAsync(
        ExecuteDispatchRequest request,
        int batchSize,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var item = FindItem(workspace, request.DispatchItemId);
        DemandExecutionPermission(context, item.Mode);
        ValidateExecutionPolicy(item, request.ConfirmationPhrase);
        ValidateProductionMode(item.Mode, batchSize);

        var prior = workspace.Attempts
            .Where(attempt => attempt.DispatchItemId == item.Id &&
                attempt.DispatchFingerprint == item.Message?.DispatchFingerprint)
            .OrderByDescending(attempt => attempt.AttemptNumber)
            .FirstOrDefault();
        if (prior?.State is DeliveryAttemptState.Pending or DeliveryAttemptState.Ambiguous)
        {
            throw Error("DISPATCH_RECONCILIATION_REQUIRED", "Resultado desconhecido: reconcilie antes de qualquer nova tentativa.");
        }

        if (prior?.State is DeliveryAttemptState.AcceptedByProvider or DeliveryAttemptState.Reconciled ||
            prior?.State == DeliveryAttemptState.DraftCreated &&
            item.Mode != DispatchOperationMode.Send &&
            !IsExternalOutbound(item.Mode))
        {
            throw Error("DISPATCH_ALREADY_PROCESSED", "Este fingerprint já possui resultado terminal; não será processado novamente.");
        }

        if (prior?.State == DeliveryAttemptState.FailedPermanent)
        {
            throw Error("DISPATCH_PERMANENT_FAILURE", "A falha permanente não é elegível para nova tentativa sem nova composição.");
        }

        var (currentItem, _) = await RevalidateAsync(workspace, item, context, cancellationToken);
        if (!Equals(currentItem, item))
        {
            var invalidated = ReplaceItem(workspace, currentItem, context, "dispatch_approval_invalidated");
            await store.SaveAsync(invalidated, cancellationToken);
            throw Error("DISPATCH_CHANGED_AFTER_APPROVAL", "A composição mudou após a aprovação; a execução foi bloqueada.");
        }

        var hasValidApproval = item.Message is not null &&
            item.Approval?.DispatchFingerprint == item.Message.DispatchFingerprint &&
            item.State is DispatchItemState.Approved or DispatchItemState.Failed or DispatchItemState.DraftCreated;
        if (!hasValidApproval)
        {
            throw Error("DISPATCH_NOT_APPROVED", "Somente mensagens com aprovação atual podem ser concluídas.");
        }

        var message = item.Message!;

        var account = await emailProvider.GetAccountAsync(cancellationToken);
        var capabilities = await emailProvider.GetCapabilitiesAsync(cancellationToken);
        if (!account.IsConnected || account.ProviderKey != options.ProviderKey || emailProvider.ProviderKey != options.ProviderKey)
        {
            throw Error("EMAIL_PROVIDER_NOT_READY", "O provedor selecionado não está disponível para a conta configurada.");
        }

        if (item.Mode == DispatchOperationMode.Draft && !capabilities.SupportsDrafts ||
            item.Mode is DispatchOperationMode.Send or DispatchOperationMode.Test && !capabilities.SupportsSending)
        {
            throw Error("EMAIL_PROVIDER_CAPABILITY_MISSING", "O provedor não oferece a capacidade exigida pelo modo.");
        }

        if (IsExternalOutbound(item.Mode))
        {
            EmailSendPreflightResponse preflight;
            try
            {
                preflight = await remoteSendGuard.AuthorizeAsync(
                    new EmailSendPreflightRequest(
                        item.Id,
                        emailProvider.ProviderKey,
                        message.DispatchFingerprint,
                        message.Attachments.Count,
                        DispatchWorkflowOptions.CurrentApplicationVersion,
                        item.Mode,
                        batchSize),
                    cancellationToken);
            }
            catch (Exception exception) when (
                exception is HttpRequestException or System.Text.Json.JsonException or InvalidOperationException)
            {
                throw Error(
                    "REMOTE_SEND_GUARD_UNAVAILABLE",
                    "O controle central/auditoria não pôde ser confirmado; a operação externa foi bloqueada.");
            }

            var remoteVersionAllowed =
                Version.TryParse(preflight.MinimumApplicationVersion, out var remoteMinimum) &&
                Version.TryParse(DispatchWorkflowOptions.CurrentApplicationVersion, out var currentVersion) &&
                currentVersion >= remoteMinimum;
            if (!preflight.Authorized ||
                !preflight.EmailSendEnabled ||
                preflight.CorrelationId == Guid.Empty ||
                !remoteVersionAllowed)
            {
                var errorCode = preflight.ErrorCode ??
                    (!preflight.Authorized
                        ? "REMOTE_SEND_NOT_AUTHORIZED"
                        : !preflight.EmailSendEnabled
                            ? "SEND_DISABLED_REMOTELY"
                            : !remoteVersionAllowed
                                ? "APP_VERSION_BELOW_MINIMUM"
                                : "REMOTE_SEND_RESPONSE_INVALID");
                throw Error(
                    errorCode,
                    "O controle central não autorizou a operação externa; nenhuma chamada ao provedor foi feita.");
            }
        }

        var attemptNumber = (prior?.AttemptNumber ?? 0) + 1;
        var attempt = new DeliveryAttempt(
            Guid.NewGuid(),
            item.BatchId,
            item.Id,
            item.GroupId,
            attemptNumber,
            item.Mode,
            DeliveryAttemptState.Pending,
            emailProvider.ProviderKey,
            CreateIdempotencyKey(item, attemptNumber),
            message.DispatchFingerprint,
            null,
            null,
            null,
            null,
            clock.UtcNow,
            null);
        var pendingItem = item with
        {
            State = item.Mode == DispatchOperationMode.Draft
                ? DispatchItemState.DraftCreating
                : DispatchItemState.Sending,
            UpdatedAtUtc = clock.UtcNow,
        };
        var pendingWorkspace = workspace with
        {
            Items = workspace.Items.Select(current => current.Id == item.Id ? pendingItem : current).ToArray(),
            Attempts = [.. workspace.Attempts, attempt],
            AuditEvents = [.. workspace.AuditEvents, Audit(context, "provider_call_started", item.BatchId, item.Id, item.GroupId, "pending")],
        };
        pendingWorkspace = SetBatchState(pendingWorkspace, item.BatchId, ProcessingBatchState.Processing);
        await store.SaveAsync(pendingWorkspace, cancellationToken);

        var envelope = new EmailEnvelope(
            item.Id,
            attempt.IdempotencyKey,
            item.Mode,
            message.DispatchFingerprint,
            message.SenderAccountId,
            message.EffectiveTo,
            message.EffectiveCc,
            message.Subject,
            message.TextBody,
            message.HtmlBody,
            message.Attachments,
            item.Scenario);
        EmailProviderResult result;
        try
        {
            result = item.Mode == DispatchOperationMode.Draft
                ? await emailProvider.CreateDraftAsync(envelope, cancellationToken)
                : await emailProvider.SendAsync(envelope, cancellationToken);
        }
        catch (Exception exception) when (exception is not DispatchWorkflowException)
        {
            result = new EmailProviderResult(
                DeliveryAttemptState.Ambiguous,
                null,
                null,
                "PROVIDER_RESULT_UNKNOWN",
                "A operação local foi interrompida antes da confirmação do resultado.");
        }

        var completedAttempt = attempt with
        {
            State = result.State,
            ProviderMessageId = result.ProviderMessageId,
            ProviderDraftId = result.ProviderDraftId,
            ErrorCode = result.ErrorCode,
            RedactedError = result.RedactedError,
            CompletedAtUtc = clock.UtcNow,
        };
        var completedItem = pendingItem with
        {
            State = MapItemState(result.State),
            UpdatedAtUtc = clock.UtcNow,
        };
        var completed = pendingWorkspace with
        {
            Items = pendingWorkspace.Items.Select(current => current.Id == item.Id ? completedItem : current).ToArray(),
            Attempts = pendingWorkspace.Attempts.Select(current => current.Id == attempt.Id ? completedAttempt : current).ToArray(),
            AuditEvents =
            [
                .. pendingWorkspace.AuditEvents,
                Audit(
                    context,
                    "provider_call_completed",
                    item.BatchId,
                    item.Id,
                    item.GroupId,
                    result.State.ToString(),
                    result.ErrorCode),
            ],
        };
        completed = UpdateBatchState(completed, item.BatchId);
        await store.SaveAsync(completed, CancellationToken.None);
        return completed;
    }

    public async Task<DispatchWorkspace> ExecuteBatchAsync(
        Guid batchId,
        string? confirmationPhrase,
        CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        Demand(context, AppPermissions.BatchApprove, "BATCH_EXECUTE_FORBIDDEN");
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var batch = workspace.Batches.SingleOrDefault(current => current.Id == batchId)
            ?? throw Error("DISPATCH_BATCH_NOT_FOUND", "Sequência de mensagens não encontrada.");
        if (batch.SelectionMode != ProcessingSelectionMode.Batch)
        {
            throw Error("DISPATCH_BATCH_MODE_REQUIRED", "A conclusão conjunta exige mensagens preparadas na mesma sequência.");
        }

        var approvedItems = workspace.Items
            .Where(item => batch.DispatchItemIds.Contains(item.Id) && item.IsApproved)
            .OrderBy(item => item.CreatedAtUtc)
            .ToArray();
        if (approvedItems.Length == 0)
        {
            throw Error("DISPATCH_BATCH_EMPTY", "Nenhuma mensagem aprovada está disponível para conclusão conjunta.");
        }

        ValidateProductionMode(batch.OperationMode, approvedItems.Length);

        if (batch.OperationMode == DispatchOperationMode.Send)
        {
            var attachmentCount = approvedItems.Sum(item => item.Message!.Attachments.Count);
            var expected = $"CONFIRMAR{ProviderConfirmationSuffix()} LOTE {approvedItems.Length} {attachmentCount}";
            if (!string.Equals(confirmationPhrase?.Trim(), expected, StringComparison.Ordinal))
            {
                throw Error("SEND_BATCH_CONFIRMATION_REQUIRED", $"Digite exatamente '{expected}' para confirmar a sequência.");
            }
        }

        foreach (var item in approvedItems)
        {
            workspace = await ExecuteCoreAsync(
                new ExecuteDispatchRequest(
                    item.Id,
                    item.Mode == DispatchOperationMode.Send
                        ? $"CONFIRMAR{ProviderConfirmationSuffix()} {item.Message!.Attachments.Count}"
                        : null),
                approvedItems.Length,
                cancellationToken);
            var executed = workspace.Items.Single(current => current.Id == item.Id);
            if (executed.State == DispatchItemState.Ambiguous)
            {
                break;
            }
        }

        return workspace;
    }

    public async Task<DispatchWorkspace> ReconcileAsync(
        Guid dispatchItemId,
        CancellationToken cancellationToken)
    {
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        var workspace = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var item = FindItem(workspace, dispatchItemId);
        DemandExecutionPermission(context, item.Mode);
        var attempt = workspace.Attempts
            .Where(current => current.DispatchItemId == item.Id &&
                current.State is DeliveryAttemptState.Pending or DeliveryAttemptState.Ambiguous)
            .OrderByDescending(current => current.AttemptNumber)
            .FirstOrDefault()
            ?? throw Error("DISPATCH_NOT_RECONCILABLE", "Não há tentativa pendente ou ambígua para reconciliar.");
        if (attempt.ProviderKey != emailProvider.ProviderKey || attempt.ProviderKey != options.ProviderKey)
        {
            throw Error(
                "EMAIL_PROVIDER_RECOVERY_MISMATCH",
                "A tentativa só pode ser reconciliada pelo mesmo provider que iniciou a operação.");
        }

        var result = await emailProvider.ReconcileAsync(attempt, cancellationToken);
        var reconciledState = result.State == DeliveryAttemptState.AcceptedByProvider
            ? DeliveryAttemptState.Reconciled
            : result.State;
        var reconciledAttempt = attempt with
        {
            State = reconciledState,
            ProviderMessageId = result.ProviderMessageId,
            ProviderDraftId = result.ProviderDraftId,
            ErrorCode = result.ErrorCode,
            RedactedError = result.RedactedError,
            CompletedAtUtc = clock.UtcNow,
        };
        var reconciledItem = item with
        {
            State = reconciledState == DeliveryAttemptState.Reconciled
                ? DispatchItemState.Reconciled
                : MapItemState(reconciledState),
            UpdatedAtUtc = clock.UtcNow,
        };
        var updated = workspace with
        {
            Attempts = workspace.Attempts.Select(current => current.Id == attempt.Id ? reconciledAttempt : current).ToArray(),
            Items = workspace.Items.Select(current => current.Id == item.Id ? reconciledItem : current).ToArray(),
            AuditEvents =
            [
                .. workspace.AuditEvents,
                Audit(context, "provider_reconciled", item.BatchId, item.Id, item.GroupId, reconciledState.ToString(), result.ErrorCode),
            ],
        };
        updated = UpdateBatchState(updated, item.BatchId);
        await store.SaveAsync(updated, cancellationToken);
        return updated;
    }

    public async Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        CancellationToken cancellationToken)
        => await ExportReportsAsync(directory, DispatchReportFilter.AllPeriods, cancellationToken);

    public async Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        int? year,
        int? month,
        CancellationToken cancellationToken)
        => await ExportReportsAsync(
            directory,
            (year, month) switch
            {
                (null, null) => DispatchReportFilter.AllPeriods,
                ({ } selectedYear, null) => DispatchReportFilter.ForYear(selectedYear),
                _ => new DispatchReportFilter(DispatchReportScope.Month, year, month),
            },
            cancellationToken);

    public async Task<DispatchReportResult> ExportReportsAsync(
        string directory,
        DispatchReportFilter filter,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(directory);
        ArgumentNullException.ThrowIfNull(filter);
        ValidateReportFilter(filter);
        var context = await contextAccessor.GetCurrentAsync(cancellationToken);
        Demand(context, AppPermissions.AuditExport, "AUDIT_EXPORT_FORBIDDEN");
        var dispatch = await store.LoadAsync(context.ScopeKey, cancellationToken);
        var review = await reviewService.LoadAsync(cancellationToken);
        var filteredReview = FilterReviewWorkspace(review, filter);
        var filteredDispatch = FilterDispatchWorkspace(dispatch, filteredReview, filter);
        var result = await reportExporter.ExportAsync(
            filteredDispatch,
            filteredReview,
            filter,
            directory,
            cancellationToken);
        var updated = dispatch with
        {
            AuditEvents =
            [
                .. dispatch.AuditEvents,
                Audit(context, "reports_exported", null, null, null, "completed"),
            ],
        };
        await store.SaveAsync(updated, cancellationToken);
        return result;
    }

    private static DocumentReviewWorkspace FilterReviewWorkspace(
        DocumentReviewWorkspace workspace,
        DispatchReportFilter filter)
    {
        if (filter.Scope == DispatchReportScope.AllPeriods && filter.ClientId is null)
        {
            return workspace;
        }

        var documents = workspace.Documents.Where(document =>
            ClientMatches(document.ClientId, filter.ClientId) &&
            PeriodMatches(document.Period, filter)).ToArray();
        var documentIds = documents.Select(document => document.Id).ToHashSet();
        var groups = workspace.Groups.Where(group =>
            ClientMatches(group.ClientId, filter.ClientId) &&
            (HasNoPeriodFilter(filter) ||
             group.DocumentIds.Any(documentIds.Contains) ||
             PeriodLabelMatches(group.PeriodLabel, filter))).ToArray();
        var groupIds = groups.Select(group => group.Id).ToHashSet();
        var audit = workspace.AuditEvents.Where(item =>
            item.DocumentId is { } documentId && documentIds.Contains(documentId) ||
            item.GroupId is { } groupId && groupIds.Contains(groupId) ||
            AggregateReviewAuditMatches(item, filter)).ToArray();
        return workspace with { Documents = documents, Groups = groups, AuditEvents = audit };
    }

    private static DispatchWorkspace FilterDispatchWorkspace(
        DispatchWorkspace workspace,
        DocumentReviewWorkspace review,
        DispatchReportFilter filter)
    {
        var groupIds = review.Groups.Select(group => group.Id).ToHashSet();
        var isUnfiltered = filter.Scope == DispatchReportScope.AllPeriods && filter.ClientId is null;
        var scopedItems = (isUnfiltered
                ? workspace.Items
                : workspace.Items.Where(item =>
                    ClientMatches(item.ClientId, filter.ClientId) &&
                    (HasNoPeriodFilter(filter) ||
                     groupIds.Contains(item.GroupId) ||
                     PeriodLabelMatches(item.PeriodLabel, filter))))
            .ToArray();
        var scopedItemIds = scopedItems.Select(item => item.Id).ToHashSet();
        var scopedBatchIds = scopedItems.Select(item => item.BatchId).ToHashSet();
        var audit = isUnfiltered
            ? workspace.AuditEvents.ToArray()
            : workspace.AuditEvents.Where(item => DispatchAuditMatchesScope(
                item,
                scopedItemIds,
                groupIds,
                scopedBatchIds)).ToArray();

        var items = ProjectCurrentDispatchItems(scopedItems);
        var itemIds = items.Select(item => item.Id).ToHashSet();
        var currentGroupIds = items.Select(item => item.GroupId).ToHashSet();
        var batchIds = items.Select(item => item.BatchId).ToHashSet();
        var batches = workspace.Batches
            .Where(batch => batchIds.Contains(batch.Id))
            .Select(batch => batch with
            {
                GroupIds = batch.GroupIds.Where(currentGroupIds.Contains).Distinct().ToArray(),
                DispatchItemIds = batch.DispatchItemIds.Where(itemIds.Contains).Distinct().ToArray(),
            })
            .ToArray();
        var attempts = workspace.Attempts
            .Where(attempt => itemIds.Contains(attempt.DispatchItemId))
            .ToArray();
        return workspace with
        {
            Batches = batches,
            Items = items,
            Attempts = attempts,
            AuditEvents = audit,
        };
    }

    private static DispatchItem[] ProjectCurrentDispatchItems(IEnumerable<DispatchItem> items) => items
        .Where(item => item.State != DispatchItemState.Cancelled)
        .GroupBy(item => item.GroupId)
        .Select(group => group
            .OrderByDescending(item => item.Revision)
            .ThenByDescending(item => item.CreatedAtUtc)
            .ThenByDescending(item => item.UpdatedAtUtc)
            .ThenByDescending(item => item.Id)
            .First())
        .ToArray();

    private static bool DispatchAuditMatchesScope(
        DispatchAuditEvent audit,
        HashSet<Guid> itemIds,
        HashSet<Guid> groupIds,
        HashSet<Guid> batchIds)
    {
        if (audit.DispatchItemId is { } itemId)
        {
            return itemIds.Contains(itemId);
        }

        if (audit.GroupId is { } groupId)
        {
            return groupIds.Contains(groupId);
        }

        return audit.BatchId is { } batchId && batchIds.Contains(batchId);
    }

    private static bool AggregateReviewAuditMatches(
        ReviewAuditEvent audit,
        DispatchReportFilter filter)
    {
        if (!string.Equals(audit.Action, "groups.client_approved", StringComparison.Ordinal) ||
            string.IsNullOrWhiteSpace(audit.NewValue))
        {
            return false;
        }

        var clientValue = GetAuditValue(audit.NewValue, "client");
        if (filter.ClientId is { } clientId &&
            !string.Equals(clientValue, clientId.ToString("N"), StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (HasNoPeriodFilter(filter))
        {
            return true;
        }

        return (GetAuditValue(audit.NewValue, "periods") ?? string.Empty)
            .Split('|', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Any(period => PeriodLabelMatches(period, filter));
    }

    private static string? GetAuditValue(string value, string key) => value
        .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Select(part => part.Split(':', 2, StringSplitOptions.TrimEntries))
        .FirstOrDefault(parts =>
            parts.Length == 2 &&
            string.Equals(parts[0], key, StringComparison.OrdinalIgnoreCase))?
        .ElementAtOrDefault(1);

    private static void ValidateReportFilter(DispatchReportFilter filter)
    {
        if (filter.ClientId == Guid.Empty ||
            filter.Scope == DispatchReportScope.Client && filter.ClientId is null)
        {
            throw new DispatchWorkflowException(
                "REPORT_CLIENT_REQUIRED",
                "Escolha o cliente que deve aparecer no relatório.");
        }

        if (filter.Scope == DispatchReportScope.Month &&
            (filter.Year is not (>= 1900 and <= 9999) || filter.Month is not (>= 1 and <= 12)))
        {
            throw new DispatchWorkflowException(
                "REPORT_MONTH_REQUIRED",
                "Escolha um ano e um mês válidos para este relatório.");
        }

        if (filter.Scope == DispatchReportScope.Year && filter.Year is not (>= 1900 and <= 9999))
        {
            throw new DispatchWorkflowException(
                "REPORT_YEAR_REQUIRED",
                "Escolha um ano válido para este relatório.");
        }

        if (filter.Scope != DispatchReportScope.Range)
        {
            return;
        }

        if (filter.StartYear is not (>= 1900 and <= 9999) ||
            filter.StartMonth is not (>= 1 and <= 12) ||
            filter.EndYear is not (>= 1900 and <= 9999) ||
            filter.EndMonth is not (>= 1 and <= 12))
        {
            throw new DispatchWorkflowException(
                "REPORT_RANGE_REQUIRED",
                "Escolha competências inicial e final válidas para este relatório.");
        }

        if (PeriodKey(filter.StartYear.Value, filter.StartMonth.Value) >
            PeriodKey(filter.EndYear.Value, filter.EndMonth.Value))
        {
            throw new DispatchWorkflowException(
                "REPORT_RANGE_INVERTED",
                "A competência inicial não pode ser posterior à competência final.");
        }
    }

    private static bool ClientMatches(Guid? value, Guid? expected) =>
        expected is null || value == expected;

    private static bool HasNoPeriodFilter(DispatchReportFilter filter) =>
        filter.Scope is DispatchReportScope.AllPeriods or DispatchReportScope.Client;

    private static bool PeriodMatches(DocumentPeriod period, DispatchReportFilter filter)
    {
        var effectiveYear = period.Year ?? period.StartDate?.Year;
        var effectiveMonth = period.Month ?? period.StartDate?.Month;
        return PeriodMatches(effectiveYear, effectiveMonth, filter);
    }

    private static bool PeriodMatches(int? year, int? month, DispatchReportFilter filter) =>
        filter.Scope switch
        {
            DispatchReportScope.AllPeriods or DispatchReportScope.Client => true,
            DispatchReportScope.Month => year == filter.Year && month == filter.Month,
            DispatchReportScope.Year => year == filter.Year,
            DispatchReportScope.Range when year is not null && month is not null =>
                PeriodKey(year.Value, month.Value) >= PeriodKey(filter.StartYear!.Value, filter.StartMonth!.Value) &&
                PeriodKey(year.Value, month.Value) <= PeriodKey(filter.EndYear!.Value, filter.EndMonth!.Value),
            _ => false,
        };

    private static bool PeriodLabelMatches(string periodLabel, DispatchReportFilter filter)
    {
        if (HasNoPeriodFilter(filter))
        {
            return true;
        }

        var numbers = periodLabel.Split(['/', '-', ':', '_', ' '], StringSplitOptions.RemoveEmptyEntries)
            .Select(part => int.TryParse(part, out var value) ? value : -1)
            .Where(value => value >= 0)
            .ToArray();
        var yearIndex = Array.FindIndex(numbers, value => value is >= 1900 and <= 9999);
        var parsedYear = yearIndex < 0 ? 0 : numbers[yearIndex];
        var parsedMonth = yearIndex switch
        {
            > 0 when numbers[yearIndex - 1] is >= 1 and <= 12 => numbers[yearIndex - 1],
            >= 0 when yearIndex + 1 < numbers.Length && numbers[yearIndex + 1] is >= 1 and <= 12 =>
                numbers[yearIndex + 1],
            _ => 0,
        };
        return PeriodMatches(
            parsedYear == 0 ? null : parsedYear,
            parsedMonth == 0 ? null : parsedMonth,
            filter);
    }

    private static int PeriodKey(int year, int month) => (year * 12) + month;

    private static string DispatchCompetenceKey(
        DocumentDispatchGroup group,
        DocumentReviewWorkspace review)
    {
        var documentCompetences = review.Documents
            .Where(document => group.DocumentIds.Contains(document.Id))
            .Select(document =>
            {
                var year = document.Period.Year ?? document.Period.StartDate?.Year;
                var month = document.Period.Month ?? document.Period.StartDate?.Month;
                return year is not null && month is not null
                    ? $"month:{year:0000}-{month:00}"
                    : document.Period.CanonicalKey;
            })
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        return documentCompetences.Length == 1
            ? documentCompetences[0]
            : group.PeriodKey;
    }

    private static long NextDispatchRevision(DispatchWorkspace workspace, Guid groupId) =>
        checked(workspace.Items
            .Where(item => item.GroupId == groupId)
            .Select(item => item.Revision)
            .DefaultIfEmpty(0L)
            .Max() + 1L);

    private async Task<(DispatchItem Item, DocumentReviewWorkspace Review)> RevalidateAsync(
        DispatchWorkspace workspace,
        DispatchItem item,
        DispatchExecutionContext context,
        CancellationToken cancellationToken)
    {
        var review = await reviewService.LoadAsync(cancellationToken);
        var group = review.Groups.SingleOrDefault(current => current.Id == item.GroupId);
        if (group?.ApprovalSnapshot is null || !group.IsApproved)
        {
            return (Invalidate(item, "REVIEW_APPROVAL_INVALIDATED", "A aprovação dos documentos deixou de ser válida."), review);
        }

        var documents = review.Documents.Where(document => group.DocumentIds.Contains(document.Id)).ToArray();
        var (message, blocks) = await composer.ComposeAsync(
            group,
            documents,
            item.Mode,
            item.TestDestination,
            cancellationToken);
        var changed = message?.DispatchFingerprint != item.Message?.DispatchFingerprint ||
            group.ApprovalSnapshot.Id != item.Approval?.ReviewApprovalId && item.Approval is not null ||
            group.ApprovalSnapshot.ContentHash != item.Approval?.ReviewContentHash && item.Approval is not null;
        if (!changed && blocks.Count == 0)
        {
            return (item, review);
        }

        return (item with
        {
            Message = message,
            Blocks = blocks,
            Approval = null,
            State = blocks.Any(block => block.Severity is ValidationSeverity.Error or ValidationSeverity.Blocker)
                ? DispatchItemState.Blocked
                : DispatchItemState.ReadyForApproval,
            Revision = item.Revision + 1,
            UpdatedAtUtc = clock.UtcNow,
        }, review);
    }

    private DispatchItem Invalidate(DispatchItem item, string code, string message) => item with
    {
        Approval = null,
        State = DispatchItemState.Blocked,
        Blocks = [.. item.Blocks.Where(block => block.Code != code), new DispatchBlock(code, ValidationSeverity.Blocker, message)],
        Revision = item.Revision + 1,
        UpdatedAtUtc = clock.UtcNow,
    };

    private void ValidateExecutionPolicy(DispatchItem item, string? confirmationPhrase)
    {
        ValidatePilotMode(item.Mode);
        if (options.ProviderKey is not DispatchWorkflowOptions.FakeProviderKey and
            not DispatchWorkflowOptions.MicrosoftGraphProviderKey and
            not DispatchWorkflowOptions.GmailProviderKey)
        {
            throw Error("EMAIL_PROVIDER_NOT_ALLOWED", "O provedor selecionado não pertence ao escopo autorizado.");
        }

        if (options.ProviderKey == DispatchWorkflowOptions.MicrosoftGraphProviderKey)
        {
            if (!options.MicrosoftGraphEnabled || string.IsNullOrWhiteSpace(options.MicrosoftGraphControlledRecipient))
            {
                throw Error("GRAPH_NOT_CONFIGURED", "Microsoft Graph exige app OAuth e destinatário controlado configurados.");
            }

            if (item.Scenario != FakeDeliveryScenario.Success)
            {
                throw Error("GRAPH_FAKE_SCENARIO_FORBIDDEN", "Cenários de falha simulada pertencem somente ao FakeEmailProvider.");
            }

            var effective = item.Message?.EffectiveTo.Concat(item.Message.EffectiveCc).ToArray() ?? [];
            if (effective.Length != 1 ||
                !string.Equals(effective[0], options.MicrosoftGraphControlledRecipient.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                throw Error("GRAPH_RECIPIENT_NOT_CONTROLLED", "A operação Graph foi bloqueada porque o destino efetivo não é o destinatário controlado.");
            }
        }

        if (options.ProviderKey == DispatchWorkflowOptions.GmailProviderKey)
        {
            if (!options.GmailEnabled || string.IsNullOrWhiteSpace(options.GmailControlledRecipient))
            {
                throw Error("GMAIL_NOT_CONFIGURED", "O Gmail exige app OAuth e destinatário controlado configurados.");
            }

            if (item.Scenario != FakeDeliveryScenario.Success)
            {
                throw Error("GMAIL_FAKE_SCENARIO_FORBIDDEN", "Cenários de falha simulada pertencem somente ao modo local seguro.");
            }

            var effective = item.Message?.EffectiveTo.Concat(item.Message.EffectiveCc).ToArray() ?? [];
            if (effective.Length != 1 ||
                !string.Equals(effective[0], options.GmailControlledRecipient.Trim(), StringComparison.OrdinalIgnoreCase))
            {
                throw Error("GMAIL_RECIPIENT_NOT_CONTROLLED", "A operação Gmail foi bloqueada porque o destino efetivo não é o destinatário controlado.");
            }
        }

        var sendsExternally = item.Mode == DispatchOperationMode.Send ||
            IsExternalOutbound(item.Mode);
        if (!sendsExternally)
        {
            return;
        }

        var sendEnabled = options.ProviderKey switch
        {
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => options.MicrosoftGraphSendEnabled,
            DispatchWorkflowOptions.GmailProviderKey => options.GmailSendEnabled,
            _ => options.EmailSendEnabled,
        };
        if (!sendEnabled)
        {
            throw Error("EMAIL_SEND_DISABLED", "O kill switch de envio está desligado.");
        }

        if (!Version.TryParse(options.MinimumSendVersion, out var minimum) ||
            !Version.TryParse(DispatchWorkflowOptions.CurrentApplicationVersion, out var current) ||
            current < minimum)
        {
            throw Error("APP_VERSION_BELOW_MINIMUM", "A versão do aplicativo está abaixo do mínimo para a operação externa.");
        }

        if (item.Mode != DispatchOperationMode.Send)
        {
            return;
        }

        var expected = $"CONFIRMAR{ProviderConfirmationSuffix()} {item.Message?.Attachments.Count ?? 0}";
        if (!string.Equals(confirmationPhrase?.Trim(), expected, StringComparison.Ordinal))
        {
            throw Error("SEND_CONFIRMATION_REQUIRED", $"Digite exatamente '{expected}' para confirmar o Send.");
        }
    }

    private void ValidatePilotMode(DispatchOperationMode mode)
    {
        if (!options.PilotModeEnabled)
        {
            return;
        }

        var allowed = mode switch
        {
            DispatchOperationMode.Test => options.PilotAllowTest,
            DispatchOperationMode.Draft => options.PilotAllowDraft,
            DispatchOperationMode.Send => options.PilotAllowSend,
            _ => false,
        };
        if (!allowed)
        {
            var code = mode == DispatchOperationMode.Send
                ? "PILOT_SEND_DISABLED"
                : "PILOT_OPERATION_DISABLED";
            throw Error(
                code,
                mode == DispatchOperationMode.Send
                    ? "O piloto supervisionado não permite envio aos destinatários. Use Teste ou Rascunho."
                    : "Esta operação não está liberada no piloto supervisionado.");
        }
    }

    private void ValidateProductionMode(DispatchOperationMode mode, int batchSize)
    {
        if (!options.ProductionRolloutEnforced ||
            mode != DispatchOperationMode.Send ||
            options.ProviderKey == DispatchWorkflowOptions.FakeProviderKey)
        {
            return;
        }

        if (!options.ProductionRolloutReady)
        {
            throw Error(
                "PRODUCTION_ROLLOUT_CLOSED",
                "O envio real permanece fechado até todos os controles da produção gradual serem aprovados.");
        }

        if (batchSize is < 1 || batchSize > options.ProductionMaximumBatchSize)
        {
            throw Error(
                "PRODUCTION_BATCH_LIMIT_EXCEEDED",
                $"Esta sequência excede o limite gradual de {options.ProductionMaximumBatchSize} mensagens.");
        }
    }

    private void DemandExecutionPermission(DispatchExecutionContext context, DispatchOperationMode mode) =>
        Demand(
            context,
            IsExternalOutbound(mode)
                ? AppPermissions.EmailSend
                : mode switch
                {
                    DispatchOperationMode.Test => AppPermissions.DocumentsProcess,
                    DispatchOperationMode.Draft => AppPermissions.EmailDraft,
                    _ => AppPermissions.EmailSend,
                },
            "DISPATCH_EXECUTE_FORBIDDEN");

    private bool IsExternalOutbound(DispatchOperationMode mode) =>
        (options.ProviderKey is DispatchWorkflowOptions.MicrosoftGraphProviderKey or DispatchWorkflowOptions.GmailProviderKey) &&
        mode is DispatchOperationMode.Test or DispatchOperationMode.Send;

    private string ProviderConfirmationSuffix() => options.ProviderKey switch
    {
        DispatchWorkflowOptions.MicrosoftGraphProviderKey => " GRAPH",
        DispatchWorkflowOptions.GmailProviderKey => " GMAIL",
        _ => string.Empty,
    };

    private static void Demand(
        DispatchExecutionContext context,
        string permission,
        string code)
    {
        if (!context.Permissions.Contains(permission))
        {
            throw Error(code, $"Permissão obrigatória ausente: {permission}.");
        }
    }

    private DispatchWorkspace ReplaceItem(
        DispatchWorkspace workspace,
        DispatchItem item,
        DispatchExecutionContext context,
        string action) => workspace with
        {
            Items = workspace.Items.Select(current => current.Id == item.Id ? item : current).ToArray(),
            AuditEvents = [.. workspace.AuditEvents, Audit(context, action, item.BatchId, item.Id, item.GroupId, item.State.ToString())],
        };

    private DispatchWorkspace UpdateBatchState(DispatchWorkspace workspace, Guid batchId)
    {
        var batchItems = workspace.Items.Where(item => item.BatchId == batchId).ToArray();
        var state = batchItems.All(item => item.State == DispatchItemState.Approved)
            ? ProcessingBatchState.Approved
            : batchItems.Any(item => item.State is DispatchItemState.Ambiguous or DispatchItemState.Sending or DispatchItemState.DraftCreating)
                ? ProcessingBatchState.RecoveryRequired
                : batchItems.All(IsSuccessfulTerminal)
                    ? ProcessingBatchState.Completed
                    : batchItems.Any(item => item.State == DispatchItemState.Failed) && batchItems.All(IsTerminal)
                        ? ProcessingBatchState.CompletedWithErrors
                        : batchItems.Any(item => item.State == DispatchItemState.Blocked)
                            ? ProcessingBatchState.Preparing
                            : ProcessingBatchState.ReadyForReview;
        return SetBatchState(workspace, batchId, state);
    }

    private DispatchWorkspace SetBatchState(
        DispatchWorkspace workspace,
        Guid batchId,
        ProcessingBatchState state) => workspace with
        {
            Batches = workspace.Batches.Select(batch => batch.Id == batchId
                ? batch with { State = state, UpdatedAtUtc = clock.UtcNow }
                : batch).ToArray(),
        };

    private static bool IsSuccessfulTerminal(DispatchItem item) => item.State is
        DispatchItemState.DraftCreated or
        DispatchItemState.AcceptedByProvider or
        DispatchItemState.Reconciled or
        DispatchItemState.Completed;

    private static bool IsTerminal(DispatchItem item) =>
        IsSuccessfulTerminal(item) || item.State is DispatchItemState.Failed or DispatchItemState.Cancelled;

    private static DispatchItemState MapItemState(DeliveryAttemptState state) => state switch
    {
        DeliveryAttemptState.DraftCreated => DispatchItemState.DraftCreated,
        DeliveryAttemptState.AcceptedByProvider => DispatchItemState.AcceptedByProvider,
        DeliveryAttemptState.FailedTransient or DeliveryAttemptState.FailedPermanent => DispatchItemState.Failed,
        DeliveryAttemptState.Ambiguous or DeliveryAttemptState.Pending => DispatchItemState.Ambiguous,
        DeliveryAttemptState.Reconciled => DispatchItemState.Reconciled,
        _ => DispatchItemState.Failed,
    };

    private static string CreateIdempotencyKey(DispatchItem item, int attemptNumber) =>
        $"phase7:{item.Mode}:{item.Id:N}:{item.Message!.DispatchFingerprint}:attempt:{attemptNumber}";

    private DispatchAuditEvent Audit(
        DispatchExecutionContext context,
        string action,
        Guid? batchId,
        Guid? itemId,
        Guid? groupId,
        string outcome,
        string? errorCode = null) => new(
            Guid.NewGuid(),
            context.ScopeKey,
            context.ActorId,
            clock.UtcNow,
            action,
            batchId,
            itemId,
            groupId,
            outcome,
            errorCode,
            Guid.NewGuid().ToString("N"));

    private static DispatchItem FindItem(DispatchWorkspace workspace, Guid dispatchItemId) =>
        workspace.Items.SingleOrDefault(item => item.Id == dispatchItemId)
        ?? throw Error("DISPATCH_ITEM_NOT_FOUND", "Item de composição não encontrado.");

    private static DispatchWorkflowException Error(string code, string message) => new(code, message);
}
