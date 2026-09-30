package br.com.contadoresassociados.folhas.application.dispatch;

import static br.com.contadoresassociados.folhas.application.documents.ReviewFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewService;
import br.com.contadoresassociados.folhas.application.documents.ReviewFixtures;
import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DispatchWorkflowServiceTest {

    static final CancellationToken CT = CancellationToken.NONE;
    static final String CONTROLLED = "teste@escritorio.com.br";

    @TempDir
    Path dir;

    MemoryStore reviewStore;
    DocumentReviewService review;
    MemoryDispatchStore dispatchStore;
    ScriptedProvider provider;
    ScriptedGuard guard;
    Set<AppPermission> permissions;
    UUID groupId;
    Path file;

    @BeforeEach
    void setUp() throws Exception {
        reviewStore = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var client = UUID.randomUUID();
        resolver.next.set(resolved(client, "Empresa A"));
        review = ReviewFixtures.service(reviewStore, resolver);
        file = pdf(dir, "folha.pdf", "conteudo");
        var ws = review.importDocument(file.toString(), payroll(file, client, "03/2026"), CT);
        groupId = ws.groups().getFirst().id();
        review.approveGroup(groupId, CT);
        dispatchStore = new MemoryDispatchStore();
        permissions = EnumSet.allOf(AppPermission.class);
    }

    DispatchWorkflowService service(DispatchWorkflowOptions options) {
        provider = new ScriptedProvider(options.providerKey());
        guard = new ScriptedGuard();
        return new DispatchWorkflowService(dispatchStore, review, new StubComposer(options), provider,
                () -> new DispatchExecutionContext("org:1|local", "ator-1", "Ana", permissions), guard,
                (d, r, f, p) -> new DispatchReportResult(p.toString(), List.of(), 0, NOW.atOffset(ZoneOffset.UTC), "ALL"), Clock.fixed(NOW), options,
                (value, max) -> value.length() > max ? value.substring(0, max) : value.replace("529.982.247-25", "***"));
    }

    static DispatchWorkflowOptions gmail() {
        var d = DispatchWorkflowOptions.defaults();
        return new DispatchWorkflowOptions(DispatchWorkflowOptions.GMAIL_PROVIDER, "gmail:conta", false, false, true,
                true, false, true, false, 5, "1.0.0", false, false, null, true, true, CONTROLLED, d.officeName(),
                d.maximumAttachmentBytes());
    }

    DispatchItem prepareAndApprove(DispatchWorkflowService svc, DispatchOperationMode mode) {
        var ws = svc.prepare(new DispatchWorkflowService.PrepareRequest(List.of(groupId),
                ProcessingSelectionMode.INDIVIDUAL, mode, CONTROLLED, FakeDeliveryScenario.SUCCESS), CT);
        var item = ws.items().getLast();
        assertThat(item.state()).isEqualTo(DispatchItemState.READY_FOR_APPROVAL);
        ws = svc.approve(item.id(), CT);
        return ws.items().getLast();
    }

    // ------------------------------------------------------------ fluxo feliz local

    @Test
    void fakeTestFlowCompletes() {
        var svc = service(DispatchWorkflowOptions.defaults());
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        var ws = svc.execute(item.id(), null, CT);
        assertThat(ws.items().getLast().state()).isEqualTo(DispatchItemState.ACCEPTED_BY_PROVIDER);
        assertThat(ws.attempts()).singleElement().satisfies(a -> assertThat(a.attemptNumber()).isEqualTo(1));
        assertThat(guard.calls.get()).isZero();
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT)).hasMessageContaining("resultado final");
    }

    // ------------------------------------------------------------ 5.4 / 6.5

    @Test
    void exceptionAfterProviderCallBecomesAmbiguousAndRequiresReconciliation() {
        var svc = service(gmail());
        provider.onSend = e -> {
            throw new IllegalStateException("socket reset depois do POST");
        };
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        var ws = svc.execute(item.id(), null, CT);
        assertThat(ws.items().getLast().state()).isEqualTo(DispatchItemState.AMBIGUOUS);
        assertThat(ws.attempts().getFirst().state()).isEqualTo(DeliveryAttemptState.AMBIGUOUS);
        assertThat(ws.batches().getFirst().state()).isEqualTo(ProcessingBatchState.RECOVERY_REQUIRED);
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("DISPATCH_RECONCILIATION_REQUIRED"));

        provider.onReconcile = a -> new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, "m-1", null,
                null, null);
        ws = svc.reconcile(item.id(), CT);
        assertThat(ws.items().getLast().state()).isEqualTo(DispatchItemState.RECONCILED);
        var presentation = DispatchOutcomePresenter.present(ws.items().getLast(), ws.attempts().getLast());
        assertThat(presentation.deliveryStatus()).contains("não comprova entrega");
    }

    // ------------------------------------------------------------ 2.4

    @Test
    void preflightTimeoutBlocksWithoutCallingProvider() {
        var svc = service(gmail());
        guard.onAuthorize = r -> {
            throw new RuntimeException(new java.net.http.HttpTimeoutException("timeout"));
        };
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("REMOTE_SEND_GUARD_UNAVAILABLE"));
        assertThat(provider.sendCalls.get()).isZero();
        assertThat(dispatchStore.saved.attempts()).isEmpty();
    }

    @Test
    void rateLimitedPreflightIsReportedWithServerCode() {
        var svc = service(gmail());
        guard.onAuthorize = r -> new EmailSendPreflightResponse(r.operationId(), false, false, "1.0.0",
                UUID.randomUUID(), "RATE_LIMITED");
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("RATE_LIMITED"));
        assertThat(provider.sendCalls.get()).isZero();
    }

    // ------------------------------------------------------------ 2.3

    @Test
    void eachAttemptGetsItsOwnOperationIdAndAttemptsAreCapped() {
        var svc = service(gmail());
        provider.onSend = e -> new EmailProviderResult(DeliveryAttemptState.FAILED_TRANSIENT, null, null, "HTTP_503",
                "indisponível para 529.982.247-25");
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        for (int i = 0; i < DispatchWorkflowService.MAXIMUM_ATTEMPTS; i++) {
            svc.execute(item.id(), null, CT);
        }
        assertThat(guard.requests).extracting(EmailSendPreflightRequest::operationId).doesNotHaveDuplicates()
                .hasSize(DispatchWorkflowService.MAXIMUM_ATTEMPTS);
        assertThat(guard.requests).extracting(EmailSendPreflightRequest::attemptNumber).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("DISPATCH_ATTEMPTS_EXHAUSTED"));
        // 7.4: erro do provedor passou pelo redator antes de persistir.
        assertThat(dispatchStore.saved.attempts()).allSatisfy(a -> assertThat(a.redactedError()).doesNotContain("529.982"));
    }

    @Test
    void mismatchedOperationIdInGuardResponseIsRejected() {
        var svc = service(gmail());
        guard.onAuthorize = r -> new EmailSendPreflightResponse(UUID.randomUUID(), true, true, "1.0.0",
                UUID.randomUUID(), null);
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("REMOTE_SEND_RESPONSE_INVALID"));
    }

    // ------------------------------------------------------------ permissões

    @Test
    void missingPermissionsAreDenied() {
        var svc = service(DispatchWorkflowOptions.defaults());
        permissions = EnumSet.noneOf(AppPermission.class);
        assertThatThrownBy(() -> svc.prepare(new DispatchWorkflowService.PrepareRequest(List.of(groupId),
                ProcessingSelectionMode.INDIVIDUAL, DispatchOperationMode.TEST, CONTROLLED, null), CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("DISPATCH_PREPARE_FORBIDDEN"));

        permissions = EnumSet.allOf(AppPermission.class);
        var item = prepareAndApprove(svc, DispatchOperationMode.DRAFT);
        permissions = EnumSet.complementOf(EnumSet.of(AppPermission.EMAIL_DRAFT));
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("DISPATCH_EXECUTE_FORBIDDEN"));

        permissions = EnumSet.complementOf(EnumSet.of(AppPermission.AUDIT_EXPORT));
        assertThatThrownBy(() -> svc.exportReports(dir, DispatchReportFilter.ALL, CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("AUDIT_EXPORT_FORBIDDEN"));
    }

    // ------------------------------------------------------------ integridade

    @Test
    void attachmentChangedAfterApprovalBlocksExecution() throws Exception {
        var svc = service(DispatchWorkflowOptions.defaults());
        var item = prepareAndApprove(svc, DispatchOperationMode.TEST);
        Files.writeString(file, "%PDF-1.7\nalterado");
        assertThatThrownBy(() -> svc.execute(item.id(), null, CT)).isInstanceOf(DispatchWorkflowException.class);
        assertThat(provider.sendCalls.get()).isZero();
    }

    @Test
    void sendRequiresExactConfirmationPhrase() {
        var d = DispatchWorkflowOptions.defaults();
        var opts = new DispatchWorkflowOptions(d.providerKey(), d.senderAccountId(), true, false, true, true, true,
                true, false, 5, "1.0.0", false, false, null, false, false, null, d.officeName(),
                d.maximumAttachmentBytes());
        var svc = service(opts);
        var item = prepareAndApprove(svc, DispatchOperationMode.SEND);
        assertThatThrownBy(() -> svc.execute(item.id(), "confirmar", CT))
                .isInstanceOfSatisfying(DispatchWorkflowException.class,
                        e -> assertThat(e.code()).isEqualTo("SEND_CONFIRMATION_REQUIRED"));
        var ws = svc.execute(item.id(), "CONFIRMAR 1", CT);
        assertThat(ws.items().getLast().state()).isEqualTo(DispatchItemState.ACCEPTED_BY_PROVIDER);
    }

    @Test
    void staleVersionIsRejectedByStore() {
        var svc = service(DispatchWorkflowOptions.defaults());
        prepareAndApprove(svc, DispatchOperationMode.TEST);
        var stale = dispatchStore.saved;
        dispatchStore.save(stale, stale.version());
        assertThatThrownBy(() -> dispatchStore.save(stale, stale.version()))
                .isInstanceOf(WorkspaceConflictException.class);
    }

    // ------------------------------------------------------------ 2.2

    @Test
    void failedDraftIsNeverPresentedAsCreated() {
        var now = NOW.atOffset(ZoneOffset.UTC);
        var item = new DispatchItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "X", null, "03/2026",
                DispatchOperationMode.DRAFT, FakeDeliveryScenario.SUCCESS, null, DispatchItemState.FAILED, 1, null,
                null, List.of(), now, now);
        var attempt = new DeliveryAttempt(UUID.randomUUID(), item.batchId(), item.id(), item.groupId(), 1,
                DispatchOperationMode.DRAFT, DeliveryAttemptState.FAILED_PERMANENT, DispatchWorkflowOptions.GMAIL_PROVIDER,
                "k", "fp", null, null, "X", null, now, now);
        assertThat(DispatchOutcomePresenter.present(item, attempt).operationResult()).isEqualTo("Operação não concluída");
        var ambiguous = item.toBuilder().state(DispatchItemState.AMBIGUOUS).build();
        assertThat(DispatchOutcomePresenter.present(ambiguous, attempt.toBuilder()
                .state(DeliveryAttemptState.AMBIGUOUS).build()).operationResult()).isEqualTo("Resultado incerto");
        var pending = item.toBuilder().state(DispatchItemState.DRAFT_CREATING).build();
        assertThat(DispatchOutcomePresenter.present(pending, attempt.toBuilder()
                .state(DeliveryAttemptState.PENDING).build()).needsAttention()).isTrue();
    }

    @Test
    void periodLabelFilterParsesCommonFormats() {
        var march = DispatchReportFilter.month(2026, 3);
        assertThat(DispatchReportFiltering.periodLabelMatches("03/2026", march)).isTrue();
        assertThat(DispatchReportFiltering.periodLabelMatches("2026-03", march)).isTrue();
        assertThat(DispatchReportFiltering.periodLabelMatches("04/2026", march)).isFalse();
        assertThat(DispatchReportFiltering.periodLabelMatches("Março 2026", DispatchReportFilter.year(2026))).isTrue();
        assertThat(DispatchReportFiltering.periodLabelMatches("12/2025",
                DispatchReportFilter.range(2025, 11, 2026, 2))).isTrue();
    }

    // ============================================================ dublês

    static final class MemoryDispatchStore implements DispatchWorkflowStore {
        DispatchWorkspace saved;

        @Override
        public DispatchWorkspace load(String scopeKey) {
            return saved == null ? DispatchWorkspace.empty(scopeKey) : saved;
        }

        @Override
        public DispatchWorkspace save(DispatchWorkspace ws, long expectedVersion) {
            var current = saved == null ? 0 : saved.version();
            if (current != expectedVersion) {
                throw new WorkspaceConflictException(ws.scopeKey(), expectedVersion, current);
            }
            saved = ws.toBuilder().version(current + 1).build();
            return saved;
        }
    }

    static final class ScriptedProvider implements EmailProvider {
        final String key;
        final AtomicInteger sendCalls = new AtomicInteger();
        Function<EmailEnvelope, EmailProviderResult> onSend = e -> new EmailProviderResult(
                DeliveryAttemptState.ACCEPTED_BY_PROVIDER, "msg-" + e.idempotencyKey().hashCode(), null, null, null);
        Function<DeliveryAttempt, EmailProviderResult> onReconcile = a -> new EmailProviderResult(
                DeliveryAttemptState.AMBIGUOUS, null, null, null, null);

        ScriptedProvider(String key) {
            this.key = key;
        }

        @Override
        public String providerKey() {
            return key;
        }

        @Override
        public EmailProviderAccount account() {
            return new EmailProviderAccount(key, "conta", "Conta", true);
        }

        @Override
        public EmailProviderCapabilities capabilities() {
            return new EmailProviderCapabilities(true, true, true, 25L * 1024 * 1024);
        }

        @Override
        public EmailProviderResult createDraft(EmailEnvelope envelope) {
            sendCalls.incrementAndGet();
            return new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, null, "d-1", null, null);
        }

        @Override
        public EmailProviderResult send(EmailEnvelope envelope) {
            sendCalls.incrementAndGet();
            return onSend.apply(envelope);
        }

        @Override
        public EmailProviderResult reconcile(DeliveryAttempt attempt) {
            return onReconcile.apply(attempt);
        }
    }

    static final class ScriptedGuard implements RemoteEmailSendGuard {
        final AtomicInteger calls = new AtomicInteger();
        final List<EmailSendPreflightRequest> requests = new ArrayList<>();
        Function<EmailSendPreflightRequest, EmailSendPreflightResponse> onAuthorize = r ->
                new EmailSendPreflightResponse(r.operationId(), true, true, "1.0.0", UUID.randomUUID(), null);

        @Override
        public EmailSendPreflightResponse authorize(EmailSendPreflightRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return onAuthorize.apply(request);
        }
    }

    /** Compositor mínimo: destino controlado e anexos com hash real dos documentos. */
    record StubComposer(DispatchWorkflowOptions options) implements DispatchMessageComposer {
        @Override
        public Composition compose(DocumentDispatchGroup group, List<ReviewDocument> documents,
                DispatchOperationMode mode, String testDestination, DispatchExecutionContext context) {
            var attachments = documents.stream().map(d -> new DispatchAttachmentSnapshot(d.id(), d.localPath(),
                    d.fileName(), d.sha256(), d.fileSizeBytes(), d.documentType())).toList();
            var fp = group.approvalSnapshot() == null ? "none" : group.approvalSnapshot().contentHash() + ":" + mode;
            var msg = new RenderedMessageSnapshot(UUID.randomUUID(), 1, "Assunto", UUID.randomUUID(), 1, "Corpo",
                    options.senderAccountId(), List.of(), List.of(), List.of(CONTROLLED), List.of(), "Folha 03/2026",
                    "Segue a folha.", "<p>Segue a folha.</p>", attachments, fp, NOW.atOffset(ZoneOffset.UTC));
            return new Composition(msg, List.of());
        }
    }
}
