package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewService;
import br.com.contadoresassociados.folhas.application.security.SensitiveTextRedactor;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Preparação, aprovação e execução de mensagens.
 *
 * <p>Correções em relação à versão .NET:
 * <ul>
 *   <li>2.3 — cada tentativa tem OperationId próprio no preflight (uma autorização não cobre retentativas).</li>
 *   <li>2.4 — qualquer falha do preflight (inclusive timeout) bloqueia com REMOTE_SEND_GUARD_UNAVAILABLE.</li>
 *   <li>4.7 — execução em lote faz um único preflight para a sequência inteira.</li>
 *   <li>5.1 — em Send com produção liberada, envia aos destinatários reais do cadastro.</li>
 *   <li>5.4 — qualquer exceção depois de iniciar a chamada ao provedor vira AMBIGUOUS (nunca "nada aconteceu").</li>
 *   <li>7.4 — mensagens de erro do provedor passam pelo redator antes de serem gravadas.</li>
 *   <li>3.13 — gravação com concorrência otimista; operações serializadas por lock.</li>
 * </ul>
 */
public final class DispatchWorkflowService {

    /** Máximo de tentativas por item e fingerprint (pendência 2.3). */
    public static final int MAXIMUM_ATTEMPTS = 3;

    private final DispatchWorkflowStore store;
    private final DocumentReviewService review;
    private final DispatchMessageComposer composer;
    private final EmailProvider provider;
    private final DispatchExecutionContextAccessor contextAccessor;
    private final RemoteEmailSendGuard guard;
    private final DispatchReportExporter exporter;
    private final Clock clock;
    private final DispatchWorkflowOptions options;
    private final SensitiveTextRedactor redactor;
    private final ReentrantLock gate = new ReentrantLock();

    public DispatchWorkflowService(DispatchWorkflowStore store, DocumentReviewService review,
            DispatchMessageComposer composer, EmailProvider provider, DispatchExecutionContextAccessor contextAccessor,
            RemoteEmailSendGuard guard, DispatchReportExporter exporter, Clock clock, DispatchWorkflowOptions options,
            SensitiveTextRedactor redactor) {
        this.store = store;
        this.review = review;
        this.composer = composer;
        this.provider = provider;
        this.contextAccessor = contextAccessor;
        this.guard = guard;
        this.exporter = exporter;
        this.clock = clock;
        this.options = options;
        this.redactor = redactor;
    }

    public record PrepareRequest(List<UUID> groupIds, ProcessingSelectionMode selectionMode,
            DispatchOperationMode operationMode, String testDestination, FakeDeliveryScenario scenario) {
    }

    public DispatchWorkflowOptions options() {
        return options;
    }

    // ------------------------------------------------------------------ carregar

    public DispatchWorkspace load(CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            var ws = store.load(ctx.scopeKey());
            var rev = review.load(ct);
            var changed = false;
            for (var item : ws.items()) {
                if (item.approval() != null && (item.state() == DispatchItemState.APPROVED
                        || item.state() == DispatchItemState.FAILED)) {
                    var current = revalidate(item, rev, ctx);
                    if (!current.equals(item)) {
                        ws = replaceItem(ws, current, ctx, "dispatch_approval_invalidated");
                        ws = updateBatchState(ws, item.batchId());
                        changed = true;
                    }
                }
            }
            return changed ? save(ws) : ws;
        });
    }

    // ------------------------------------------------------------------ preparar

    public DispatchWorkspace prepare(PrepareRequest request, CancellationToken ct) {
        Objects.requireNonNull(request, "request");
        validatePilotMode(request.operationMode());
        var groupIds = List.copyOf(new LinkedHashSet<>(request.groupIds()));
        validateProductionMode(request.operationMode(), groupIds.size());
        if (groupIds.isEmpty() || request.selectionMode() == ProcessingSelectionMode.INDIVIDUAL && groupIds.size() != 1) {
            throw error("DISPATCH_SELECTION_INVALID",
                    "Selecione um conjunto para um cliente ou vários conjuntos para preparar em sequência.");
        }
        return locked(() -> {
            var ctx = contextAccessor.current();
            demand(ctx, AppPermission.DOCUMENTS_PROCESS, "DISPATCH_PREPARE_FORBIDDEN");
            var rev = review.load(ct);
            var groups = groupIds.stream().map(id -> rev.groups().stream().filter(g -> g.id().equals(id)).findFirst()
                    .orElseThrow(() -> error("REVIEW_GROUP_NOT_FOUND", "Conjunto " + id + " não encontrado."))).toList();
            if (request.selectionMode() == ProcessingSelectionMode.BATCH
                    && groups.stream().map(g -> competenceKey(g, rev)).distinct().count() > 1) {
                throw error("DISPATCH_PERIOD_MISMATCH",
                        "Uma sequência de mensagens deve conter conjuntos de uma única competência mensal.");
            }
            var ws = store.load(ctx.scopeKey());
            var ids = Set.copyOf(groupIds);
            if (ws.attempts().stream().anyMatch(a -> ids.contains(a.groupId()) && unresolved(a.state()))) {
                throw error("DISPATCH_RECONCILIATION_REQUIRED",
                        "Há resultado pendente ou incerto neste conjunto; confira a situação antes de preparar outra mensagem.");
            }
            var now = clock.nowUtc();
            var items = ws.items().stream().map(i -> ids.contains(i.groupId()) && !isTerminal(i)
                    ? i.toBuilder().state(DispatchItemState.CANCELLED).approval(null).updatedAtUtc(now).build() : i)
                    .collect(Collectors.toCollection(ArrayList::new));
            var audits = new ArrayList<>(ws.auditEvents());
            var batchId = UUID.randomUUID();
            var created = new ArrayList<DispatchItem>();
            for (var g : groups) {
                ct.throwIfCancellationRequested();
                var docs = documentsOf(g, rev);
                var comp = composer.compose(g, docs, request.operationMode(), request.testDestination(), ctx);
                var blocks = new ArrayList<>(comp.blocks());
                if (!g.isApproved() || g.approvalSnapshot() == null) {
                    blocks.add(new DispatchBlock("REVIEW_GROUP_NOT_APPROVED", ValidationSeverity.BLOCKER,
                            "O conjunto de documentos precisa estar liberado antes de preparar a mensagem."));
                }
                var item = new DispatchItem(UUID.randomUUID(), batchId, g.id(), g.clientId(), g.clientDisplayName(),
                        g.establishmentId(), g.periodLabel(), request.operationMode(),
                        request.scenario() == null ? FakeDeliveryScenario.SUCCESS : request.scenario(),
                        request.testDestination() == null ? null : request.testDestination().strip().toLowerCase(Locale.ROOT),
                        blocking(blocks) ? DispatchItemState.BLOCKED : DispatchItemState.READY_FOR_APPROVAL,
                        nextRevision(ws, g.id()), comp.message(), null, blocks, now, now);
                created.add(item);
                audits.add(audit(ctx, "dispatch_composed", batchId, item.id(), g.id(), item.state().name(), null));
            }
            items.addAll(created);
            var batches = new ArrayList<>(ws.batches());
            batches.add(new ProcessingBatch(batchId, ctx.scopeKey(), request.selectionMode(), request.operationMode(),
                    created.stream().allMatch(i -> i.state() == DispatchItemState.READY_FOR_APPROVAL)
                            ? ProcessingBatchState.READY_FOR_REVIEW : ProcessingBatchState.PREPARING,
                    groupIds, created.stream().map(DispatchItem::id).toList(), ctx.actorId(), now, now));
            return save(ws.toBuilder().batches(batches).items(items).auditEvents(audits).build());
        });
    }

    // ------------------------------------------------------------------ aprovar

    public DispatchWorkspace approve(UUID itemId, CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            demand(ctx, AppPermission.DOCUMENTS_PROCESS, "DISPATCH_APPROVE_FORBIDDEN");
            var ws = store.load(ctx.scopeKey());
            var item = find(ws, itemId);
            var batch = batch(ws, item.batchId());
            if (batch.selectionMode() == ProcessingSelectionMode.BATCH) {
                demand(ctx, AppPermission.BATCH_APPROVE, "BATCH_APPROVE_FORBIDDEN");
            }
            var rev = review.load(ct);
            var current = revalidate(item, rev, ctx);
            if (!current.equals(item)) {
                save(replaceItem(ws, current, ctx, "dispatch_approval_invalidated"));
                throw error("DISPATCH_CHANGED_AFTER_REVIEW",
                        "Destinatários, modelo ou documentos mudaram; revise e aprove novamente.");
            }
            if (item.state() != DispatchItemState.READY_FOR_APPROVAL || item.preventsApproval() || item.message() == null) {
                throw error("DISPATCH_NOT_APPROVABLE", "O item contém bloqueios ou não está pronto para aprovação.");
            }
            var updated = replaceItem(ws, approved(item, rev, ctx), ctx, "dispatch_approved");
            return save(updateBatchState(updated, item.batchId()));
        });
    }

    public DispatchWorkspace approveBatch(UUID batchId, CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            demand(ctx, AppPermission.DOCUMENTS_PROCESS, "DISPATCH_APPROVE_FORBIDDEN");
            demand(ctx, AppPermission.BATCH_APPROVE, "BATCH_APPROVE_FORBIDDEN");
            var ws = store.load(ctx.scopeKey());
            var batch = batch(ws, batchId);
            if (batch.selectionMode() != ProcessingSelectionMode.BATCH) {
                throw error("DISPATCH_BATCH_MODE_REQUIRED",
                        "A aprovação conjunta exige mensagens preparadas na mesma sequência.");
            }
            var ids = Set.copyOf(batch.dispatchItemIds());
            var candidates = ws.items().stream().filter(i -> ids.contains(i.id())
                    && i.state() == DispatchItemState.READY_FOR_APPROVAL && !i.preventsApproval() && i.message() != null)
                    .toList();
            if (candidates.isEmpty()) {
                throw error("DISPATCH_BATCH_EMPTY",
                        "Nenhuma mensagem elegível está pronta; itens bloqueados ficam fora da aprovação conjunta.");
            }
            var rev = review.load(ct);
            for (var item : candidates) {
                var current = revalidate(item, rev, ctx);
                ws = !current.equals(item) ? replaceItem(ws, current, ctx, "dispatch_approval_invalidated")
                        : replaceItem(ws, approved(item, rev, ctx), ctx, "dispatch_bulk_approved");
            }
            return save(updateBatchState(ws, batchId));
        });
    }

    // ------------------------------------------------------------------ executar

    public DispatchWorkspace execute(UUID itemId, String confirmationPhrase, CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            var ws = store.load(ctx.scopeKey());
            var item = find(ws, itemId);
            var rev = review.load(ct);
            var authorization = preflight(List.of(item), ws, ctx, 1).get(item.id());
            return executeOne(ws, item, confirmationPhrase, 1, rev, ctx, authorization, ct);
        });
    }

    public DispatchWorkspace executeBatch(UUID batchId, String confirmationPhrase, CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            demand(ctx, AppPermission.BATCH_APPROVE, "BATCH_EXECUTE_FORBIDDEN");
            var ws = store.load(ctx.scopeKey());
            var batch = batch(ws, batchId);
            if (batch.selectionMode() != ProcessingSelectionMode.BATCH) {
                throw error("DISPATCH_BATCH_MODE_REQUIRED",
                        "A conclusão conjunta exige mensagens preparadas na mesma sequência.");
            }
            var ids = Set.copyOf(batch.dispatchItemIds());
            var approvedItems = ws.items().stream().filter(i -> ids.contains(i.id()) && i.isApproved())
                    .sorted(Comparator.comparing(DispatchItem::createdAtUtc)).toList();
            if (approvedItems.isEmpty()) {
                throw error("DISPATCH_BATCH_EMPTY", "Nenhuma mensagem aprovada está disponível para conclusão conjunta.");
            }
            validateProductionMode(batch.operationMode(), approvedItems.size());
            if (batch.operationMode() == DispatchOperationMode.SEND) {
                var attachments = approvedItems.stream().mapToInt(i -> i.message().attachments().size()).sum();
                var expected = "CONFIRMAR" + options.confirmationSuffix() + " LOTE " + approvedItems.size() + " "
                        + attachments;
                if (confirmationPhrase == null || !expected.equals(confirmationPhrase.strip())) {
                    throw error("SEND_BATCH_CONFIRMATION_REQUIRED",
                            "Digite exatamente '" + expected + "' para confirmar a sequência.");
                }
            }
            var rev = review.load(ct);
            var authorizations = preflight(approvedItems, ws, ctx, approvedItems.size());
            for (var item : approvedItems) {
                if (ct.isCancellationRequested()) {
                    ws = save(appendAudit(ws, audit(ctx, "dispatch_batch_paused", batchId, null, null, "paused", null)));
                    break;
                }
                var phrase = item.mode() == DispatchOperationMode.SEND
                        ? "CONFIRMAR" + options.confirmationSuffix() + " " + item.message().attachments().size() : null;
                ws = executeOne(ws, find(ws, item.id()), phrase, approvedItems.size(), rev, ctx,
                        authorizations.get(item.id()), ct);
                if (find(ws, item.id()).state() == DispatchItemState.AMBIGUOUS) {
                    break;
                }
            }
            return ws;
        });
    }

    private DispatchWorkspace executeOne(DispatchWorkspace ws, DispatchItem item, String phrase, int batchSize,
            DocumentReviewWorkspace rev, DispatchExecutionContext ctx, EmailSendPreflightResponse authorization,
            CancellationToken ct) {
        demandExecution(ctx, item.mode());
        validateExecutionPolicy(item, phrase);
        validateProductionMode(item.mode(), batchSize);
        var prior = lastAttempt(ws, item);
        if (prior != null && unresolved(prior.state())) {
            throw error("DISPATCH_RECONCILIATION_REQUIRED",
                    "Resultado desconhecido: confira a situação antes de qualquer nova tentativa.");
        }
        if (prior != null && (prior.state() == DeliveryAttemptState.ACCEPTED_BY_PROVIDER
                || prior.state() == DeliveryAttemptState.RECONCILED
                || prior.state() == DeliveryAttemptState.DRAFT_CREATED && item.mode() != DispatchOperationMode.SEND
                        && !externalOutbound(item.mode()))) {
            throw error("DISPATCH_ALREADY_PROCESSED",
                    "Este conteúdo já tem resultado final; não será processado novamente.");
        }
        if (prior != null && prior.state() == DeliveryAttemptState.FAILED_PERMANENT) {
            throw error("DISPATCH_PERMANENT_FAILURE",
                    "A falha permanente não permite nova tentativa sem preparar a mensagem de novo.");
        }
        var attemptNumber = prior == null ? 1 : prior.attemptNumber() + 1;
        if (attemptNumber > MAXIMUM_ATTEMPTS) {
            throw error("DISPATCH_ATTEMPTS_EXHAUSTED",
                    "Limite de " + MAXIMUM_ATTEMPTS + " tentativas atingido; prepare a mensagem de novo.");
        }
        var current = revalidate(item, rev, ctx);
        if (!current.equals(item)) {
            save(replaceItem(ws, current, ctx, "dispatch_approval_invalidated"));
            throw error("DISPATCH_CHANGED_AFTER_APPROVAL",
                    "A composição mudou após a aprovação; a execução foi bloqueada.");
        }
        var message = item.message();
        var validApproval = message != null && item.approval() != null
                && message.dispatchFingerprint().equals(item.approval().dispatchFingerprint())
                && (item.state() == DispatchItemState.APPROVED || item.state() == DispatchItemState.FAILED
                        || item.state() == DispatchItemState.DRAFT_CREATED);
        if (!validApproval) {
            throw error("DISPATCH_NOT_APPROVED", "Somente mensagens com aprovação atual podem ser concluídas.");
        }
        verifyAttachmentsUnchanged(message);
        var account = provider.account();
        var caps = provider.capabilities();
        if (!account.isConnected() || !options.providerKey().equals(account.providerKey())
                || !options.providerKey().equals(provider.providerKey())) {
            throw error("EMAIL_PROVIDER_NOT_READY", "O provedor selecionado não está disponível para a conta configurada.");
        }
        if (item.mode() == DispatchOperationMode.DRAFT && !caps.supportsDrafts()
                || item.mode() != DispatchOperationMode.DRAFT && !caps.supportsSending()) {
            throw error("EMAIL_PROVIDER_CAPABILITY_MISSING", "O provedor não oferece a capacidade exigida pelo modo.");
        }
        if (externalOutbound(item.mode())) {
            checkAuthorization(authorization);
        }

        var now = clock.nowUtc();
        var attempt = new DeliveryAttempt(UUID.randomUUID(), item.batchId(), item.id(), item.groupId(), attemptNumber,
                item.mode(), DeliveryAttemptState.PENDING, provider.providerKey(), idempotencyKey(item, attemptNumber),
                message.dispatchFingerprint(), null, null, null, null, now, null);
        var pending = item.toBuilder().state(item.mode() == DispatchOperationMode.DRAFT
                ? DispatchItemState.DRAFT_CREATING : DispatchItemState.SENDING).updatedAtUtc(now).build();
        var attempts = new ArrayList<>(ws.attempts());
        attempts.add(attempt);
        var pendingWs = replaceItemSilently(ws, pending).toBuilder().attempts(attempts).build();
        pendingWs = appendAudit(pendingWs, audit(ctx, "provider_call_started", item.batchId(), item.id(), item.groupId(),
                "pending", null));
        pendingWs = setBatchState(pendingWs, item.batchId(), ProcessingBatchState.PROCESSING);
        pendingWs = save(pendingWs);

        var envelope = new EmailEnvelope(item.id(), attempt.idempotencyKey(), item.mode(),
                message.dispatchFingerprint(), message.senderAccountId(), message.effectiveTo(), message.effectiveCc(),
                message.subject(), message.textBody(), message.htmlBody(), message.attachments(), item.scenario());
        EmailProviderResult result;
        try {
            result = item.mode() == DispatchOperationMode.DRAFT ? provider.createDraft(envelope) : provider.send(envelope);
            if (result == null) {
                result = ambiguous("O provedor não devolveu resultado.");
            }
        } catch (RuntimeException e) {
            // Pendência 5.4 / 6.5: a chamada pode ter chegado ao provedor; nunca afirmar que nada aconteceu.
            result = ambiguous("A operação foi interrompida depois de iniciada; o resultado no serviço é desconhecido.");
        }
        return complete(pendingWs, pending, attempt, result, ctx);
    }

    private DispatchWorkspace complete(DispatchWorkspace ws, DispatchItem pending, DeliveryAttempt attempt,
            EmailProviderResult result, DispatchExecutionContext ctx) {
        var now = clock.nowUtc();
        var done = attempt.toBuilder().state(result.state()).providerMessageId(result.providerMessageId())
                .providerDraftId(result.providerDraftId()).errorCode(result.errorCode())
                .redactedError(result.redactedError() == null ? null : redactor.redact(result.redactedError(), 500))
                .completedAtUtc(now).build();
        var item = pending.toBuilder().state(mapState(result.state())).updatedAtUtc(now).build();
        var attempts = ws.attempts().stream().map(a -> a.id().equals(done.id()) ? done : a).toList();
        var updated = replaceItemSilently(ws, item).toBuilder().attempts(attempts).build();
        updated = appendAudit(updated, audit(ctx, "provider_call_completed", item.batchId(), item.id(), item.groupId(),
                result.state().name(), result.errorCode()));
        return save(updateBatchState(updated, item.batchId()));
    }

    // ------------------------------------------------------------------ preflight

    private Map<UUID, EmailSendPreflightResponse> preflight(List<DispatchItem> items, DispatchWorkspace ws,
            DispatchExecutionContext ctx, int batchSize) {
        var external = items.stream().filter(i -> externalOutbound(i.mode()) && i.message() != null).toList();
        if (external.isEmpty()) {
            return Map.of();
        }
        var requests = external.stream().map(i -> {
            var prior = lastAttempt(ws, i);
            var attempt = prior == null ? 1 : prior.attemptNumber() + 1;
            return new EmailSendPreflightRequest(operationId(i, attempt), provider.providerKey(),
                    i.message().dispatchFingerprint(), i.message().attachments().size(), AppVersion.CURRENT.toString(),
                    i.mode(), batchSize, attempt);
        }).toList();
        List<EmailSendPreflightResponse> responses;
        try {
            responses = guard.authorizeBatch(requests);
        } catch (RuntimeException e) {
            throw error("REMOTE_SEND_GUARD_UNAVAILABLE",
                    "O controle central/auditoria não pôde ser confirmado; a operação externa foi bloqueada.");
        }
        if (responses == null || responses.size() != requests.size()) {
            throw error("REMOTE_SEND_RESPONSE_INVALID", "O controle central devolveu uma resposta incompleta.");
        }
        var map = new HashMap<UUID, EmailSendPreflightResponse>();
        for (var i = 0; i < external.size(); i++) {
            var resp = responses.get(i);
            if (resp == null || !requests.get(i).operationId().equals(resp.operationId())) {
                throw error("REMOTE_SEND_RESPONSE_INVALID", "O controle central devolveu uma resposta incoerente.");
            }
            map.put(external.get(i).id(), resp);
        }
        return map;
    }

    private static void checkAuthorization(EmailSendPreflightResponse p) {
        if (p == null) {
            throw error("REMOTE_SEND_GUARD_UNAVAILABLE",
                    "O controle central/auditoria não pôde ser confirmado; a operação externa foi bloqueada.");
        }
        var versionOk = AppVersion.parse(p.minimumApplicationVersion()).map(AppVersion.CURRENT::isAtLeast).orElse(false);
        if (!p.authorized() || !p.emailSendEnabled() || p.correlationId() == null
                || new UUID(0, 0).equals(p.correlationId()) || !versionOk) {
            var code = p.errorCode() != null ? p.errorCode()
                    : !p.authorized() ? "REMOTE_SEND_NOT_AUTHORIZED"
                    : !p.emailSendEnabled() ? "SEND_DISABLED_REMOTELY"
                    : !versionOk ? "APP_VERSION_BELOW_MINIMUM" : "REMOTE_SEND_RESPONSE_INVALID";
            throw error(code, "O controle central não autorizou a operação externa; nenhuma chamada ao provedor foi feita.");
        }
    }

    /** OperationId determinístico por item e tentativa (pendência 2.3). */
    static UUID operationId(DispatchItem item, int attempt) {
        return UUID.nameUUIDFromBytes((item.id() + ":" + item.message().dispatchFingerprint() + ":" + attempt)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ reconciliar e relatórios

    public DispatchWorkspace reconcile(UUID itemId, CancellationToken ct) {
        return locked(() -> {
            var ctx = contextAccessor.current();
            var ws = store.load(ctx.scopeKey());
            var item = find(ws, itemId);
            demandExecution(ctx, item.mode());
            var attempt = ws.attempts().stream().filter(a -> a.dispatchItemId().equals(item.id()) && unresolved(a.state()))
                    .max(Comparator.comparingInt(DeliveryAttempt::attemptNumber))
                    .orElseThrow(() -> error("DISPATCH_NOT_RECONCILABLE",
                            "Não há tentativa pendente ou incerta para conferir."));
            if (!attempt.providerKey().equals(provider.providerKey()) || !attempt.providerKey().equals(options.providerKey())) {
                throw error("EMAIL_PROVIDER_RECOVERY_MISMATCH",
                        "A tentativa só pode ser conferida pelo mesmo provedor que iniciou a operação.");
            }
            EmailProviderResult result;
            try {
                result = provider.reconcile(attempt);
            } catch (RuntimeException e) {
                result = ambiguous("A consulta ao serviço falhou; tente conferir de novo mais tarde.");
            }
            var state = result.state() == DeliveryAttemptState.ACCEPTED_BY_PROVIDER ? DeliveryAttemptState.RECONCILED
                    : result.state();
            var done = attempt.toBuilder().state(state).providerMessageId(result.providerMessageId())
                    .providerDraftId(result.providerDraftId()).errorCode(result.errorCode())
                    .redactedError(result.redactedError() == null ? null : redactor.redact(result.redactedError(), 500))
                    .completedAtUtc(clock.nowUtc()).build();
            var newItem = item.toBuilder().state(state == DeliveryAttemptState.RECONCILED ? DispatchItemState.RECONCILED
                    : mapState(state)).updatedAtUtc(clock.nowUtc()).build();
            var attempts = ws.attempts().stream().map(a -> a.id().equals(done.id()) ? done : a).toList();
            var updated = replaceItemSilently(ws, newItem).toBuilder().attempts(attempts).build();
            updated = appendAudit(updated, audit(ctx, "provider_reconciled", item.batchId(), item.id(), item.groupId(),
                    state.name(), result.errorCode()));
            return save(updateBatchState(updated, item.batchId()));
        });
    }

    /** Pendência 10.10: reconcilia automaticamente tudo o que ficou pendente ou incerto. */
    public int reconcileAllPending(CancellationToken ct) {
        var ctx = contextAccessor.current();
        var pending = store.load(ctx.scopeKey()).attempts().stream()
                .filter(a -> unresolved(a.state()) && a.providerKey().equals(provider.providerKey()))
                .map(DeliveryAttempt::dispatchItemId).distinct().toList();
        var count = 0;
        for (var id : pending) {
            ct.throwIfCancellationRequested();
            try {
                reconcile(id, ct);
                count++;
            } catch (DispatchWorkflowException ignored) {
                // item sem permissão ou já resolvido; segue com os demais
            }
        }
        return count;
    }

    public DispatchReportResult exportReports(Path directory, DispatchReportFilter filter, CancellationToken ct) {
        Objects.requireNonNull(directory, "directory");
        filter.validate();
        return locked(() -> {
            var ctx = contextAccessor.current();
            demand(ctx, AppPermission.AUDIT_EXPORT, "AUDIT_EXPORT_FORBIDDEN");
            var ws = store.load(ctx.scopeKey());
            var rev = review.load(ct);
            var filteredReview = DispatchReportFiltering.filterReview(rev, filter);
            var filteredDispatch = DispatchReportFiltering.filterDispatch(ws, filteredReview, filter);
            var result = exporter.export(filteredDispatch, filteredReview, filter, directory);
            save(appendAudit(ws, audit(ctx, "reports_exported", null, null, null, "completed", null)));
            return result;
        });
    }

    // ------------------------------------------------------------------ revalidação e políticas

    private DispatchItem revalidate(DispatchItem item, DocumentReviewWorkspace rev, DispatchExecutionContext ctx) {
        var group = rev.groups().stream().filter(g -> g.id().equals(item.groupId())).findFirst().orElse(null);
        if (group == null || group.approvalSnapshot() == null || !group.isApproved()) {
            return invalidate(item, "REVIEW_APPROVAL_INVALIDATED", "A aprovação dos documentos deixou de ser válida.");
        }
        var comp = composer.compose(group, documentsOf(group, rev), item.mode(), item.testDestination(), ctx);
        var newFingerprint = comp.message() == null ? null : comp.message().dispatchFingerprint();
        var oldFingerprint = item.message() == null ? null : item.message().dispatchFingerprint();
        var changed = !Objects.equals(newFingerprint, oldFingerprint) || item.approval() != null
                && (!group.approvalSnapshot().id().equals(item.approval().reviewApprovalId())
                        || !group.approvalSnapshot().contentHash().equals(item.approval().reviewContentHash()));
        if (!changed && comp.blocks().isEmpty()) {
            return item;
        }
        return item.toBuilder().message(comp.message()).blocks(comp.blocks()).approval(null)
                .state(blocking(comp.blocks()) ? DispatchItemState.BLOCKED : DispatchItemState.READY_FOR_APPROVAL)
                .revision(item.revision() + 1).updatedAtUtc(clock.nowUtc()).build();
    }

    private DispatchItem invalidate(DispatchItem item, String code, String message) {
        var blocks = new ArrayList<>(item.blocks().stream().filter(b -> !b.code().equals(code)).toList());
        blocks.add(new DispatchBlock(code, ValidationSeverity.BLOCKER, message));
        return item.toBuilder().approval(null).state(DispatchItemState.BLOCKED).blocks(blocks)
                .revision(item.revision() + 1).updatedAtUtc(clock.nowUtc()).build();
    }

    private DispatchItem approved(DispatchItem item, DocumentReviewWorkspace rev, DispatchExecutionContext ctx) {
        var group = rev.groups().stream().filter(g -> g.id().equals(item.groupId())).findFirst().orElseThrow();
        var now = clock.nowUtc();
        return item.toBuilder().state(DispatchItemState.APPROVED).approval(new DispatchApprovalSnapshot(UUID.randomUUID(),
                item.id(), item.revision(), group.approvalSnapshot().id(), group.approvalSnapshot().contentHash(),
                item.message().dispatchFingerprint(), ctx.actorId(), now)).updatedAtUtc(now).build();
    }

    private void verifyAttachmentsUnchanged(RenderedMessageSnapshot message) {
        for (var a : message.attachments()) {
            try {
                var hash = review.fingerprints().sha256Fresh(Path.of(a.localPath()));
                if (!hash.equalsIgnoreCase(a.sha256())) {
                    throw error("ATTACHMENT_CHANGED", "O anexo " + a.fileName()
                            + " mudou depois da aprovação; a execução foi bloqueada.");
                }
            } catch (java.io.IOException e) {
                throw error("ATTACHMENT_NOT_FOUND", "O anexo " + a.fileName() + " não pôde ser lido.");
            }
        }
    }

    private void validateExecutionPolicy(DispatchItem item, String phrase) {
        validatePilotMode(item.mode());
        var key = options.providerKey();
        if (!Set.of(DispatchWorkflowOptions.FAKE_PROVIDER, DispatchWorkflowOptions.GRAPH_PROVIDER,
                DispatchWorkflowOptions.GMAIL_PROVIDER).contains(key)) {
            throw error("EMAIL_PROVIDER_NOT_ALLOWED", "O provedor selecionado não pertence ao escopo autorizado.");
        }
        if (options.isExternalProvider()) {
            var prefix = DispatchWorkflowOptions.GRAPH_PROVIDER.equals(key) ? "GRAPH" : "GMAIL";
            if (!options.providerEnabled()) {
                throw error(prefix + "_NOT_CONFIGURED", "A conta de e-mail precisa estar habilitada e configurada.");
            }
            if (item.scenario() != FakeDeliveryScenario.SUCCESS) {
                throw error(prefix + "_FAKE_SCENARIO_FORBIDDEN", "Cenários de falha simulada só existem no modo local.");
            }
            var realRecipients = item.mode() == DispatchOperationMode.SEND && options.productionRolloutReady();
            if (!realRecipients) {
                var controlled = options.controlledRecipient();
                var effective = item.message() == null ? List.<String>of()
                        : java.util.stream.Stream.concat(item.message().effectiveTo().stream(),
                                item.message().effectiveCc().stream()).toList();
                if (controlled == null || effective.size() != 1 || !effective.getFirst().equalsIgnoreCase(controlled.strip())) {
                    throw error(prefix + "_RECIPIENT_NOT_CONTROLLED",
                            "A operação foi bloqueada porque o destino efetivo não é a caixa de teste controlada.");
                }
            }
        }
        if (item.mode() != DispatchOperationMode.SEND && !externalOutbound(item.mode())) {
            return;
        }
        if (!options.providerSendEnabled()) {
            throw error("EMAIL_SEND_DISABLED", "O envio está desligado nesta instalação.");
        }
        var minimum = AppVersion.parse(options.minimumSendVersion());
        if (minimum.isEmpty() || !AppVersion.CURRENT.isAtLeast(minimum.get())) {
            throw error("APP_VERSION_BELOW_MINIMUM", "A versão do aplicativo está abaixo do mínimo para a operação externa.");
        }
        if (item.mode() != DispatchOperationMode.SEND) {
            return;
        }
        var expected = "CONFIRMAR" + options.confirmationSuffix() + " "
                + (item.message() == null ? 0 : item.message().attachments().size());
        if (phrase == null || !expected.equals(phrase.strip())) {
            throw error("SEND_CONFIRMATION_REQUIRED", "Digite exatamente '" + expected + "' para confirmar o envio.");
        }
    }

    private void validatePilotMode(DispatchOperationMode mode) {
        if (!options.pilotModeEnabled()) {
            return;
        }
        var allowed = switch (mode) {
            case TEST -> options.pilotAllowTest();
            case DRAFT -> options.pilotAllowDraft();
            case SEND -> options.pilotAllowSend();
        };
        if (!allowed) {
            throw mode == DispatchOperationMode.SEND
                    ? error("PILOT_SEND_DISABLED", "O piloto supervisionado não permite envio aos destinatários. Use Teste ou Rascunho.")
                    : error("PILOT_OPERATION_DISABLED", "Esta operação não está liberada no piloto supervisionado.");
        }
    }

    private void validateProductionMode(DispatchOperationMode mode, int batchSize) {
        if (!options.productionRolloutEnforced() || mode != DispatchOperationMode.SEND
                || DispatchWorkflowOptions.FAKE_PROVIDER.equals(options.providerKey())) {
            return;
        }
        if (!options.productionRolloutReady()) {
            throw error("PRODUCTION_ROLLOUT_CLOSED",
                    "O envio real permanece fechado até todos os controles da produção gradual serem aprovados.");
        }
        if (batchSize < 1 || batchSize > options.productionMaximumBatchSize()) {
            throw error("PRODUCTION_BATCH_LIMIT_EXCEEDED",
                    "Esta sequência excede o limite gradual de " + options.productionMaximumBatchSize() + " mensagens.");
        }
    }

    private void demandExecution(DispatchExecutionContext ctx, DispatchOperationMode mode) {
        var permission = externalOutbound(mode) ? AppPermission.EMAIL_SEND : switch (mode) {
            case TEST -> AppPermission.DOCUMENTS_PROCESS;
            case DRAFT -> AppPermission.EMAIL_DRAFT;
            case SEND -> AppPermission.EMAIL_SEND;
        };
        demand(ctx, permission, "DISPATCH_EXECUTE_FORBIDDEN");
    }

    private boolean externalOutbound(DispatchOperationMode mode) {
        return options.isExternalProvider() && (mode == DispatchOperationMode.TEST || mode == DispatchOperationMode.SEND);
    }

    private static void demand(DispatchExecutionContext ctx, AppPermission permission, String code) {
        if (!ctx.has(permission)) {
            throw error(code, "Seu perfil não tem a permissão necessária (" + permission.wireName() + ").");
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static EmailProviderResult ambiguous(String message) {
        return new EmailProviderResult(DeliveryAttemptState.AMBIGUOUS, null, null, "PROVIDER_RESULT_UNKNOWN", message);
    }

    private static boolean unresolved(DeliveryAttemptState s) {
        return s == DeliveryAttemptState.PENDING || s == DeliveryAttemptState.AMBIGUOUS;
    }

    private static boolean blocking(List<DispatchBlock> blocks) {
        return blocks.stream().anyMatch(b -> b.severity() == ValidationSeverity.ERROR
                || b.severity() == ValidationSeverity.BLOCKER);
    }

    private static DeliveryAttempt lastAttempt(DispatchWorkspace ws, DispatchItem item) {
        var fp = item.message() == null ? null : item.message().dispatchFingerprint();
        return ws.attempts().stream().filter(a -> a.dispatchItemId().equals(item.id())
                && Objects.equals(a.dispatchFingerprint(), fp))
                .max(Comparator.comparingInt(DeliveryAttempt::attemptNumber)).orElse(null);
    }

    private static List<ReviewDocument> documentsOf(DocumentDispatchGroup g, DocumentReviewWorkspace rev) {
        var ids = Set.copyOf(g.documentIds());
        return rev.documents().stream().filter(d -> ids.contains(d.id())).toList();
    }

    private static String competenceKey(DocumentDispatchGroup g, DocumentReviewWorkspace rev) {
        var keys = documentsOf(g, rev).stream().map(d -> {
            var p = d.period();
            Integer y = p.year() != null ? p.year() : p.startDate() == null ? null : p.startDate().getYear();
            Integer m = p.month() != null ? p.month() : p.startDate() == null ? null : p.startDate().getMonthValue();
            return y != null && m != null ? "month:%04d-%02d".formatted(y, m) : p.canonicalKey();
        }).distinct().toList();
        return keys.size() == 1 ? keys.getFirst() : g.periodKey();
    }

    private static long nextRevision(DispatchWorkspace ws, UUID groupId) {
        return ws.items().stream().filter(i -> i.groupId().equals(groupId)).mapToLong(DispatchItem::revision).max()
                .orElse(0) + 1;
    }

    private static boolean isSuccessfulTerminal(DispatchItem i) {
        return switch (i.state()) {
            case DRAFT_CREATED, ACCEPTED_BY_PROVIDER, RECONCILED, COMPLETED -> true;
            default -> false;
        };
    }

    private static boolean isTerminal(DispatchItem i) {
        return isSuccessfulTerminal(i) || i.state() == DispatchItemState.FAILED || i.state() == DispatchItemState.CANCELLED;
    }

    static DispatchItemState mapState(DeliveryAttemptState s) {
        return switch (s) {
            case DRAFT_CREATED -> DispatchItemState.DRAFT_CREATED;
            case ACCEPTED_BY_PROVIDER -> DispatchItemState.ACCEPTED_BY_PROVIDER;
            case FAILED_TRANSIENT, FAILED_PERMANENT -> DispatchItemState.FAILED;
            case AMBIGUOUS, PENDING -> DispatchItemState.AMBIGUOUS;
            case RECONCILED -> DispatchItemState.RECONCILED;
        };
    }

    private static String idempotencyKey(DispatchItem item, int attempt) {
        return "phase7:" + DeterministicDispatchMessageComposer.modeName(item.mode()) + ":"
                + item.id().toString().replace("-", "") + ":" + item.message().dispatchFingerprint() + ":attempt:" + attempt;
    }

    private DispatchWorkspace updateBatchState(DispatchWorkspace ws, UUID batchId) {
        var items = ws.items().stream().filter(i -> i.batchId().equals(batchId)).toList();
        ProcessingBatchState state;
        if (items.stream().allMatch(i -> i.state() == DispatchItemState.APPROVED)) {
            state = ProcessingBatchState.APPROVED;
        } else if (items.stream().anyMatch(i -> i.state() == DispatchItemState.AMBIGUOUS
                || i.state() == DispatchItemState.SENDING || i.state() == DispatchItemState.DRAFT_CREATING)) {
            state = ProcessingBatchState.RECOVERY_REQUIRED;
        } else if (items.stream().allMatch(DispatchWorkflowService::isSuccessfulTerminal)) {
            state = ProcessingBatchState.COMPLETED;
        } else if (items.stream().anyMatch(i -> i.state() == DispatchItemState.FAILED)
                && items.stream().allMatch(DispatchWorkflowService::isTerminal)) {
            state = ProcessingBatchState.COMPLETED_WITH_ERRORS;
        } else if (items.stream().anyMatch(i -> i.state() == DispatchItemState.BLOCKED)) {
            state = ProcessingBatchState.PREPARING;
        } else {
            state = ProcessingBatchState.READY_FOR_REVIEW;
        }
        return setBatchState(ws, batchId, state);
    }

    private DispatchWorkspace setBatchState(DispatchWorkspace ws, UUID batchId, ProcessingBatchState state) {
        var now = clock.nowUtc();
        return ws.toBuilder().batches(ws.batches().stream().map(b -> b.id().equals(batchId)
                ? b.toBuilder().state(state).updatedAtUtc(now).build() : b).toList()).build();
    }

    private DispatchWorkspace replaceItem(DispatchWorkspace ws, DispatchItem item, DispatchExecutionContext ctx,
            String action) {
        return appendAudit(replaceItemSilently(ws, item), audit(ctx, action, item.batchId(), item.id(), item.groupId(),
                item.state().name(), null));
    }

    private static DispatchWorkspace replaceItemSilently(DispatchWorkspace ws, DispatchItem item) {
        return ws.toBuilder().items(ws.items().stream().map(i -> i.id().equals(item.id()) ? item : i).toList()).build();
    }

    private static DispatchWorkspace appendAudit(DispatchWorkspace ws, DispatchAuditEvent event) {
        var audits = new ArrayList<>(ws.auditEvents());
        audits.add(event);
        return ws.toBuilder().auditEvents(audits).build();
    }

    private DispatchAuditEvent audit(DispatchExecutionContext ctx, String action, UUID batchId, UUID itemId,
            UUID groupId, String outcome, String errorCode) {
        return new DispatchAuditEvent(UUID.randomUUID(), ctx.scopeKey(), ctx.actorId(), clock.nowUtc(), action, batchId,
                itemId, groupId, outcome, errorCode, UUID.randomUUID().toString().replace("-", ""));
    }

    private static DispatchItem find(DispatchWorkspace ws, UUID id) {
        return ws.items().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> error("DISPATCH_ITEM_NOT_FOUND", "Mensagem não encontrada."));
    }

    private static ProcessingBatch batch(DispatchWorkspace ws, UUID id) {
        return ws.batches().stream().filter(b -> b.id().equals(id)).findFirst()
                .orElseThrow(() -> error("DISPATCH_BATCH_NOT_FOUND", "Sequência de mensagens não encontrada."));
    }

    private DispatchWorkspace save(DispatchWorkspace ws) {
        // Concorrência otimista (3.13): a versão esperada é a que foi carregada por esta operação.
        return store.save(ws, ws.version());
    }

    private <T> T locked(java.util.function.Supplier<T> action) {
        gate.lock();
        try {
            return action.get();
        } finally {
            gate.unlock();
        }
    }

    private static DispatchWorkflowException error(String code, String message) {
        return new DispatchWorkflowException(code, message);
    }
}
