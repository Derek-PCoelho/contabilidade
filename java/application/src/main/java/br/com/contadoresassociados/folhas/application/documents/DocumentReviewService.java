package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Revisão de documentos: validação, agrupamento, correções humanas e aprovação.
 *
 * <p>Correções em relação à versão .NET (relatório, seção 2):
 * <ul>
 *   <li>2.1 — "cliente mudou" vira trava persistente ({@code pendingClientChangeFrom}); só uma
 *       confirmação humana ({@link #overrideClient}) a remove. Override manual é avaliado antes
 *       da comparação automática.</li>
 *   <li>2.6 — a revalidação envia ao resolvedor só os campos elegíveis, com evidência minimizada.</li>
 *   <li>2.7 — resolução em lote (uma chamada por revalidação) e hash de arquivo em cache.</li>
 *   <li>2.8 — o rótulo do grupo vem da competência do grupo, não do último documento.</li>
 *   <li>3.13 — salvamento com versão esperada (concorrência otimista).</li>
 *   <li>6.20 — importação de vários PDFs grava e revalida uma única vez.</li>
 * </ul>
 */
public final class DocumentReviewService {

    public record Import(String localPath, DocumentRecognitionResult recognition) {
    }

    private final DocumentReviewStore store;
    private final DocumentReviewContextAccessor contextAccessor;
    private final DocumentPeriodParser periodParser;
    private final List<ValidationRule> rules;
    private final DocumentReviewOptions options;
    private final Clock clock;
    private final ClientResolver clientResolver;
    private final FileFingerprintCache fingerprints = new FileFingerprintCache();
    private final ReentrantLock gate = new ReentrantLock();

    public DocumentReviewService(DocumentReviewStore store, DocumentReviewContextAccessor contextAccessor,
            DocumentPeriodParser periodParser, List<ValidationRule> rules, DocumentReviewOptions options, Clock clock,
            ClientResolver clientResolver) {
        this.store = Objects.requireNonNull(store);
        this.contextAccessor = Objects.requireNonNull(contextAccessor);
        this.periodParser = Objects.requireNonNull(periodParser);
        this.rules = List.copyOf(rules);
        this.options = Objects.requireNonNull(options);
        this.clock = Objects.requireNonNull(clock);
        this.clientResolver = clientResolver;
    }

    public FileFingerprintCache fingerprints() {
        return fingerprints;
    }

    // ------------------------------------------------------------------ operações públicas

    public DocumentReviewWorkspace load(CancellationToken ct) {
        return execute(ct, (ws, ctx) -> revalidate(ws, ctx, false, ct));
    }

    public DocumentReviewWorkspace importDocument(String localPath, DocumentRecognitionResult recognition,
            CancellationToken ct) {
        return importDocuments(List.of(new Import(localPath, recognition)), ct);
    }

    public DocumentReviewWorkspace importDocuments(List<Import> imports, CancellationToken ct) {
        if (imports.isEmpty()) {
            throw new DocumentReviewException("import.empty", "Selecione ao menos um PDF para importar.");
        }
        return execute(ct, (ws, ctx) -> {
            var docs = new ArrayList<>(ws.documents());
            var audits = new ArrayList<>(ws.auditEvents());
            var now = clock.nowUtc();
            for (var imp : imports) {
                ct.throwIfCancellationRequested();
                var r = imp.recognition();
                var res = r.resolution();
                var doc = new ReviewDocument(UUID.randomUUID(), Path.of(imp.localPath()).toAbsolutePath().normalize()
                        .toString(), r.fileName(), r.sha256().toUpperCase(Locale.ROOT), r.fileSizeBytes(),
                        r.pageCount(), r.documentType(), r.profileVersion(), res.clientId(), res.establishmentId(),
                        res.clientDisplayName(), res.clientTaxIdMasked(), res.method(), res.confidence(),
                        res.alternatives(), res.blockers(), r.fields(), r.findings(), periodParser.parse(r.fields()),
                        "", ReviewDocumentState.BLOCKED, 1, null, List.of(), now, now, null, null);
                docs.add(doc);
                audits.add(audit(ctx, "document.imported", doc.id(), null, null, r.documentType().name(),
                        "Importação local; caminho absoluto e conteúdo não são sincronizados."));
            }
            var updated = ws.toBuilder().documents(docs).auditEvents(audits).build();
            return revalidate(updated, ctx, true, ct);
        });
    }

    public DocumentReviewWorkspace revalidate(CancellationToken ct) {
        return execute(ct, (ws, ctx) -> {
            var updated = revalidate(ws, ctx, true, ct);
            return withAudit(updated, audit(ctx, "workspace.revalidated", null, null, null,
                    "documents:" + updated.documents().size(), null));
        });
    }

    public DocumentReviewWorkspace correctPeriod(UUID documentId, DocumentPeriod period, String reason,
            CancellationToken ct) {
        Objects.requireNonNull(period, "period");
        validateReason(reason, "period.change_reason_required");
        validateCorrectedPeriod(period);
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var index = m.documentIndex(documentId);
            var doc = m.documents.get(index);
            var corrected = period.toBuilder().dueDate(period.dueDate() != null ? period.dueDate()
                    : doc.period().dueDate()).build();
            if (periodsEquivalent(doc.period(), corrected)) {
                throw new DocumentReviewException("period.correction_unchanged",
                        "A competência informada é igual à competência atual do documento.");
            }
            if (doc.groupId() != null) {
                detach(m, ctx, doc.id(), doc.groupId(), "Competência corrigida manualmente.");
                doc = m.documents.get(index);
            }
            m.documents.set(index, doc.toBuilder().period(corrected).periodOverride(corrected).groupId(null)
                    .revision(doc.revision() + 1).validatedAtUtc(clock.nowUtc()).build());
            m.audits.add(audit(ctx, "document.period_corrected", doc.id(), null, doc.period().displayLabel(),
                    corrected.displayLabel(), reason.strip()));
            return revalidate(m.build(), ctx, true, ct);
        });
    }

    public DocumentReviewWorkspace restoreExtractedPeriod(UUID documentId, String reason, CancellationToken ct) {
        validateReason(reason, "period.change_reason_required");
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var index = m.documentIndex(documentId);
            var doc = m.documents.get(index);
            if (doc.periodOverride() == null) {
                throw new DocumentReviewException("period.override_not_found",
                        "Este documento já usa a competência reconhecida no PDF.");
            }
            var previous = doc.periodOverride();
            var extracted = periodParser.parse(doc.fields());
            if (doc.groupId() != null) {
                detach(m, ctx, doc.id(), doc.groupId(), "Correção manual da competência removida.");
                doc = m.documents.get(index);
            }
            m.documents.set(index, doc.toBuilder().period(extracted).periodOverride(null).groupId(null)
                    .revision(doc.revision() + 1).validatedAtUtc(clock.nowUtc()).build());
            m.audits.add(audit(ctx, "document.period_restored", doc.id(), null, previous.displayLabel(),
                    extracted.displayLabel(), reason.strip()));
            return revalidate(m.build(), ctx, true, ct);
        });
    }

    public DocumentReviewWorkspace removeDocument(UUID documentId, String reason, CancellationToken ct) {
        validateReason(reason, "document.removal_reason_required");
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var index = m.documentIndex(documentId);
            var doc = m.documents.get(index);
            if (doc.groupId() != null) {
                detach(m, ctx, doc.id(), doc.groupId(), "Documento retirado da revisão ativa.");
            }
            m.documents.remove(index);
            m.audits.add(audit(ctx, "document.removed_from_review", doc.id(), doc.groupId(),
                    "client:" + (doc.clientId() == null ? "-" : hex(doc.clientId())) + ";client-name:"
                            + sanitize(doc.clientDisplayName()) + ";type:" + doc.documentType() + ";period:"
                            + doc.period().canonicalKey() + ";sha256:" + doc.sha256(),
                    null, reason.strip()));
            var updated = pruneEmptyGroups(m.build(), ctx, "Grupo removido da revisão ativa porque ficou sem documentos.");
            return revalidate(updated, ctx, true, ct);
        });
    }

    /**
     * Associa manualmente o documento a um candidato autenticado. Também é a única forma de
     * confirmar uma troca de cliente pendente (pendência 2.1).
     */
    public DocumentReviewWorkspace overrideClient(UUID documentId, ClientResolutionCandidate candidate, String reason,
            CancellationToken ct) {
        Objects.requireNonNull(candidate, "candidate");
        validateReason(reason, "client.override_reason_required");
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var index = m.documentIndex(documentId);
            var doc = m.documents.get(index);
            var trusted = doc.clientAlternatives().stream()
                    .filter(a -> a.clientId().equals(candidate.clientId())
                            && Objects.equals(a.establishmentId(), candidate.establishmentId()))
                    .findFirst()
                    .orElseThrow(() -> new DocumentReviewException("client.override_candidate_not_trusted",
                            "O cliente informado não pertence às alternativas autenticadas deste documento."));
            if (doc.groupId() != null) {
                detach(m, ctx, doc.id(), doc.groupId(), "Cliente alterado manualmente.");
                doc = m.documents.get(index);
            }
            var previous = (doc.clientDisplayName() == null ? "Não resolvido" : doc.clientDisplayName()) + " ("
                    + (doc.clientTaxIdMasked() == null ? "***" : doc.clientTaxIdMasked()) + ")";
            var current = trusted.displayName() + " (" + trusted.taxIdMasked() + ")";
            m.documents.set(index, doc.toBuilder().clientId(trusted.clientId()).establishmentId(trusted.establishmentId())
                    .clientDisplayName(trusted.displayName()).clientTaxIdMasked(trusted.taxIdMasked())
                    .resolutionMethod(ClientResolutionMethod.MANUAL_OVERRIDE).resolutionConfidence(trusted.confidence())
                    .resolutionBlockers(List.of()).pendingClientChangeFrom(null).groupId(null)
                    .state(ReviewDocumentState.BLOCKED).revision(doc.revision() + 1).build());
            m.audits.add(audit(ctx, doc.pendingClientChangeFrom() != null ? "document.client_change_confirmed"
                    : "document.client_overridden", doc.id(), null, previous, current, reason.strip()));
            return revalidate(m.build(), ctx, true, ct);
        });
    }

    public DocumentReviewWorkspace splitGroup(UUID groupId, Collection<UUID> documentIds, String reason,
            CancellationToken ct) {
        validateReason(reason, "group.change_reason_required");
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var index = m.groupIndex(groupId);
            var group = m.groups.get(index);
            var selected = new LinkedHashSet<>(documentIds);
            if (selected.isEmpty() || selected.size() >= group.documentIds().size()
                    || !group.documentIds().containsAll(selected)) {
                throw new DocumentReviewException("group.split_invalid_selection",
                        "Selecione uma parte não vazia, mas não todos os documentos do grupo.");
            }
            invalidateApproval(m, index, ctx, "Grupo separado manualmente.");
            group = m.groups.get(index);
            var now = clock.nowUtc();
            var remaining = group.documentIds().stream().filter(id -> !selected.contains(id)).toList();
            m.groups.set(index, group.toBuilder().documentIds(remaining).revision(group.revision() + 1)
                    .state(ReviewGroupState.READY_FOR_REVIEW).updatedAtUtc(now).build());
            var splitId = UUID.randomUUID();
            m.groups.add(group.toBuilder().id(splitId).groupingKey(group.groupingKey() + "|manual:" + hex(splitId))
                    .state(ReviewGroupState.READY_FOR_REVIEW).revision(1).documentIds(List.copyOf(selected))
                    .approvalSnapshot(null).createdAtUtc(now).updatedAtUtc(now).build());
            for (var i = 0; i < m.documents.size(); i++) {
                var d = m.documents.get(i);
                if (selected.contains(d.id())) {
                    m.documents.set(i, d.toBuilder().groupId(splitId).state(ReviewDocumentState.GROUPED).build());
                }
            }
            m.audits.add(audit(ctx, "group.split", null, group.id(), "documents:" + group.documentIds().size(),
                    "remaining:" + remaining.size() + ";new:" + selected.size(), reason.strip()));
            return recalculateGroups(m.build());
        });
    }

    public DocumentReviewWorkspace mergeGroups(UUID targetId, UUID sourceId, String reason, CancellationToken ct) {
        validateReason(reason, "group.change_reason_required");
        if (targetId.equals(sourceId)) {
            throw new DocumentReviewException("group.merge_same_group", "Escolha dois grupos diferentes.");
        }
        return execute(ct, (ws, ctx) -> {
            var m = new Mutable(ws);
            var target = m.groups.get(m.groupIndex(targetId));
            var source = m.groups.get(m.groupIndex(sourceId));
            if (!target.clientId().equals(source.clientId())) {
                throw new DocumentReviewException("group.client_mismatch",
                        "Grupos de clientes diferentes nunca podem ser unidos.");
            }
            if (!target.periodKey().equals(source.periodKey())) {
                throw new DocumentReviewException("group.period_mismatch",
                        "Grupos de períodos contábeis diferentes não podem ser unidos.");
            }
            if (!Objects.equals(target.establishmentId(), source.establishmentId())) {
                throw new DocumentReviewException("group.establishment_mismatch",
                        "Conjuntos de estabelecimentos diferentes não podem ser unidos.");
            }
            if (!target.groupingPolicyCode().equals(source.groupingPolicyCode())
                    || !target.groupingPolicyVersion().equals(source.groupingPolicyVersion())) {
                throw new DocumentReviewException("group.policy_mismatch",
                        "Conjuntos formados por políticas ou versões diferentes não podem ser unidos.");
            }
            invalidateApproval(m, m.groupIndex(targetId), ctx, "Grupo unido manualmente.");
            invalidateApproval(m, m.groupIndex(sourceId), ctx, "Grupo unido manualmente.");
            target = m.groups.get(m.groupIndex(targetId));
            source = m.groups.get(m.groupIndex(sourceId));
            var merged = new ArrayList<>(new LinkedHashSet<UUID>() {
                {
                    addAll(m.groups.get(m.groupIndex(targetId)).documentIds());
                    addAll(m.groups.get(m.groupIndex(sourceId)).documentIds());
                }
            });
            m.groups.set(m.groupIndex(targetId), target.toBuilder()
                    .groupingKey("manual:" + hex(target.id()) + ":" + target.periodKey()).documentIds(merged)
                    .revision(target.revision() + 1).state(ReviewGroupState.READY_FOR_REVIEW)
                    .updatedAtUtc(clock.nowUtc()).build());
            m.groups.remove(m.groupIndex(sourceId));
            var sourceDocs = Set.copyOf(source.documentIds());
            for (var i = 0; i < m.documents.size(); i++) {
                var d = m.documents.get(i);
                if (sourceDocs.contains(d.id())) {
                    m.documents.set(i, d.toBuilder().groupId(targetId).state(ReviewDocumentState.GROUPED).build());
                }
            }
            m.audits.add(audit(ctx, "group.merged", null, targetId, "groups:" + hex(targetId) + "," + hex(sourceId),
                    "documents:" + merged.size(), reason.strip()));
            return recalculateGroups(m.build());
        });
    }

    public DocumentReviewWorkspace approveGroup(UUID groupId, CancellationToken ct) {
        return execute(ct, (ws, ctx) -> approveGroup(revalidate(ws, ctx, true, ct), groupId, ctx));
    }

    public DocumentReviewWorkspace approveGroups(Collection<UUID> groupIds, CancellationToken ct) {
        var requested = List.copyOf(new LinkedHashSet<>(groupIds));
        if (requested.isEmpty()) {
            throw new DocumentReviewException("groups.selection_required",
                    "Selecione ao menos um grupo pronto para aprovação.");
        }
        return execute(ct, (ws, ctx) -> {
            var updated = revalidate(ws, ctx, true, ct);
            var groups = requested.stream().map(id -> approvable(updatedRef(updated), id)).toList();
            if (groups.stream().map(g -> competenceKey(g, updated)).distinct().count() > 1) {
                throw new DocumentReviewException("groups.period_mismatch",
                        "A aprovação em lote deve conter conjuntos de uma única competência mensal.");
            }
            var result = updated;
            for (var id : requested) {
                result = approveGroup(result, id, ctx);
            }
            return withAudit(result, audit(ctx, "groups.selection_approved", null, null, null,
                    "approved:" + requested.size(), "Aprovação atômica restrita aos grupos explicitamente selecionados."));
        });
    }

    public DocumentReviewWorkspace approveClientGroups(UUID clientId, Collection<UUID> groupIds, CancellationToken ct) {
        if (clientId == null) {
            throw new DocumentReviewException("groups.client_required",
                    "Selecione um cliente válido para liberar seus conjuntos prontos.");
        }
        var requested = List.copyOf(new LinkedHashSet<>(groupIds));
        if (requested.isEmpty()) {
            throw new DocumentReviewException("groups.selection_required",
                    "Selecione ao menos um conjunto pronto deste cliente.");
        }
        return execute(ct, (ws, ctx) -> {
            var updated = revalidate(ws, ctx, true, ct);
            var groups = requested.stream().map(id -> updated.groups().stream().filter(g -> g.id().equals(id))
                    .findFirst().orElseThrow(() -> new DocumentReviewException("group.not_found",
                            "Um dos conjuntos selecionados não existe mais neste espaço de revisão."))).toList();
            if (groups.stream().anyMatch(g -> !g.clientId().equals(clientId))) {
                throw new DocumentReviewException("group.client_mismatch",
                        "Todos os conjuntos selecionados devem pertencer ao mesmo cliente informado.");
            }
            groups.forEach(g -> approvable(updated, g.id()));
            var all = updated.groups().stream().filter(g -> g.clientId().equals(clientId) && isApprovable(updated, g))
                    .map(DocumentDispatchGroup::id).collect(Collectors.toSet());
            if (!all.equals(Set.copyOf(requested))) {
                throw new DocumentReviewException("groups.client_selection_incomplete",
                        "A liberação do cliente deve incluir todos os seus conjuntos prontos.");
            }
            var docCount = groups.stream().flatMap(g -> g.documentIds().stream()).distinct().count();
            var competences = groups.stream().map(g -> competenceKey(g, updated)).distinct().count();
            var labels = groups.stream().map(DocumentDispatchGroup::periodLabel).distinct().sorted()
                    .collect(Collectors.joining("|"));
            var result = updated;
            for (var id : requested) {
                result = approveGroup(result, id, ctx);
            }
            return withAudit(result, audit(ctx, "groups.client_approved", null, null, null,
                    "client:" + hex(clientId) + ";groups:" + requested.size() + ";documents:" + docCount
                            + ";competences:" + competences + ";periods:" + labels,
                    "Aprovação humana do cliente; os conjuntos e as competências permaneceram separados."));
        });
    }

    public DocumentReviewWorkspace approveAllEligible(CancellationToken ct) {
        return execute(ct, (ws, ctx) -> {
            var updated = revalidate(ws, ctx, true, ct);
            var eligible = updated.groups().stream().filter(g -> g.state() == ReviewGroupState.READY_FOR_REVIEW
                    && !g.preventsApproval()).toList();
            if (eligible.stream().map(g -> competenceKey(g, updated)).distinct().count() > 1) {
                throw new DocumentReviewException("groups.period_mismatch",
                        "Selecione uma única competência mensal antes da aprovação em lote.");
            }
            var result = updated;
            for (var g : eligible) {
                result = approveGroup(result, g.id(), ctx);
            }
            return withAudit(result, audit(ctx, "groups.bulk_approved", null, null, null,
                    "approved:" + eligible.size(), "Somente grupos elegíveis, sem erro ou bloqueio."));
        });
    }

    // ------------------------------------------------------------------ revalidação

    private DocumentReviewWorkspace revalidate(DocumentReviewWorkspace ws, DocumentReviewContext ctx,
            boolean recordAudit, CancellationToken ct) {
        var now = clock.nowUtc();
        var accountingDate = clock.accountingDate();
        var candidates = new ArrayList<ReviewDocument>(ws.documents().size());
        for (var d : ws.documents()) {
            var period = d.periodOverride() != null ? d.periodOverride() : periodParser.parse(d.fields());
            candidates.add(d.toBuilder().period(period).build());
        }
        if (clientResolver != null && !candidates.isEmpty()) {
            var requests = candidates.stream().map(d -> ClientResolver.minimize(d.fields())).toList();
            var results = clientResolver.resolveBatch(requests);
            if (results.size() != candidates.size()) {
                throw new IllegalStateException("O resolvedor devolveu uma quantidade inesperada de resultados.");
            }
            for (var i = 0; i < candidates.size(); i++) {
                candidates.set(i, applyRefreshedResolution(candidates.get(i), results.get(i)));
            }
        }
        var evaluated = new ArrayList<ReviewDocument>(candidates.size());
        for (var candidate : candidates) {
            ct.throwIfCancellationRequested();
            var profile = options.profile(candidate.documentType());
            var vctx = new DocumentValidationContext(candidate, profile, accountingDate, now, fingerprints);
            var findings = new ArrayList<ValidationFinding>();
            for (var rule : rules) {
                findings.addAll(rule.evaluate(vctx));
            }
            var previous = ws.documents().stream().filter(x -> x.id().equals(candidate.id())).findFirst()
                    .map(ReviewDocument::findings).orElse(List.of());
            var normalized = preserveHistory(previous, findings);
            evaluated.add(candidate.toBuilder().semanticDuplicateKey(semanticKey(candidate, profile))
                    .findings(normalized)
                    .state(normalized.stream().anyMatch(ValidationFinding::preventsApproval)
                            ? ReviewDocumentState.BLOCKED : ReviewDocumentState.READY)
                    .validatedAtUtc(now).build());
        }
        applyDuplicates(evaluated, now);
        var audits = new ArrayList<>(ws.auditEvents());
        for (var i = 0; i < evaluated.size(); i++) {
            var previous = ws.documents().get(i);
            var current = evaluated.get(i);
            if (reviewChanged(previous, current)) {
                evaluated.set(i, current.toBuilder().revision(previous.revision() + 1).build());
                if (recordAudit) {
                    audits.add(audit(ctx, "document.validated", current.id(), current.groupId(),
                            previous.state().name(), current.state().name(), null));
                }
            } else {
                evaluated.set(i, current.toBuilder().revision(previous.revision())
                        .validatedAtUtc(previous.validatedAtUtc()).build());
            }
        }
        var m = new Mutable(ws.toBuilder().documents(evaluated).auditEvents(audits).build());
        detachChangedIdentity(ws.documents(), m, ctx);
        detachIneligible(m, ctx);
        var updated = attachEligible(m.build(), ctx);
        updated = pruneEmptyGroups(updated, ctx, "Grupo removido automaticamente porque ficou sem documentos ativos.");
        updated = invalidateChangedSnapshots(updated, ctx);
        return recalculateGroups(updated);
    }

    /**
     * Pendência 2.1. Ordem: (1) trava pendente persiste; (2) override manual verificado se
     * mantém; (3) mudança automática de cliente vira trava; (4) resolução normal.
     */
    ReviewDocument applyRefreshedResolution(ReviewDocument doc, ClientResolutionResult resolution) {
        var resolved = resolution.clientId() == null ? null
                : new ClientResolutionCandidate(resolution.clientId(), resolution.establishmentId(),
                        Objects.requireNonNullElse(resolution.clientDisplayName(), "Cliente resolvido"),
                        Objects.requireNonNullElse(resolution.clientTaxIdMasked(), "***"), resolution.method(),
                        resolution.confidence());
        var alternatives = new ArrayList<ClientResolutionCandidate>();
        if (resolved != null) {
            alternatives.add(resolved);
        }
        resolution.alternatives().stream().filter(a -> resolved == null || !sameTarget(a, resolved))
                .forEach(alternatives::add);

        if (doc.pendingClientChangeFrom() != null) {
            // Mantém o cliente anterior entre as alternativas para permitir confirmar a associação original.
            doc.clientAlternatives().stream()
                    .filter(a -> a.clientId().equals(doc.pendingClientChangeFrom()))
                    .filter(a -> alternatives.stream().noneMatch(x -> sameTarget(x, a)))
                    .forEach(alternatives::add);
            return doc.toBuilder().clientId(null).establishmentId(null).clientDisplayName(null)
                    .clientTaxIdMasked(null).resolutionMethod(ClientResolutionMethod.NONE)
                    .resolutionConfidence(BigDecimal.ZERO).clientAlternatives(alternatives)
                    .resolutionBlockers(withCode(resolution.blockers(), "client.assignment_changed")).build();
        }
        if (doc.resolutionMethod() == ClientResolutionMethod.MANUAL_OVERRIDE && doc.clientId() != null) {
            var verified = alternatives.stream().filter(a -> a.clientId().equals(doc.clientId())
                    && Objects.equals(a.establishmentId(), doc.establishmentId())).findFirst();
            if (verified.isPresent()) {
                var hard = resolution.blockers().stream()
                        .filter(c -> !c.equals("client.not_resolved") && !c.equals("client.cnpj_root_ambiguous"))
                        .distinct().toList();
                return doc.toBuilder().clientDisplayName(verified.get().displayName())
                        .clientTaxIdMasked(verified.get().taxIdMasked()).resolutionConfidence(verified.get().confidence())
                        .clientAlternatives(alternatives).resolutionBlockers(hard).build();
            }
            return lockChange(doc, alternatives, resolution);
        }
        if (doc.clientId() != null && resolved != null && (!resolved.clientId().equals(doc.clientId())
                || !Objects.equals(resolved.establishmentId(), doc.establishmentId()))) {
            return lockChange(doc, alternatives, resolution);
        }
        return doc.toBuilder().clientId(resolution.clientId()).establishmentId(resolution.establishmentId())
                .clientDisplayName(resolution.clientDisplayName()).clientTaxIdMasked(resolution.clientTaxIdMasked())
                .resolutionMethod(resolution.method()).resolutionConfidence(resolution.confidence())
                .clientAlternatives(resolution.alternatives()).resolutionBlockers(resolution.blockers()).build();
    }

    private static ReviewDocument lockChange(ReviewDocument doc, List<ClientResolutionCandidate> alternatives,
            ClientResolutionResult resolution) {
        var withPrevious = new ArrayList<>(alternatives);
        if (doc.clientId() != null && withPrevious.stream().noneMatch(a -> a.clientId().equals(doc.clientId())
                && Objects.equals(a.establishmentId(), doc.establishmentId()))) {
            withPrevious.add(new ClientResolutionCandidate(doc.clientId(), doc.establishmentId(),
                    Objects.requireNonNullElse(doc.clientDisplayName(), "Cliente anterior"),
                    Objects.requireNonNullElse(doc.clientTaxIdMasked(), "***"), ClientResolutionMethod.MANUAL_OVERRIDE,
                    doc.resolutionConfidence()));
        }
        return doc.toBuilder().pendingClientChangeFrom(doc.clientId()).clientId(null).establishmentId(null)
                .clientDisplayName(null).clientTaxIdMasked(null).resolutionMethod(ClientResolutionMethod.NONE)
                .resolutionConfidence(BigDecimal.ZERO).clientAlternatives(withPrevious)
                .resolutionBlockers(withCode(resolution.blockers(), "client.assignment_changed")).build();
    }

    private static boolean sameTarget(ClientResolutionCandidate a, ClientResolutionCandidate b) {
        return a.clientId().equals(b.clientId()) && Objects.equals(a.establishmentId(), b.establishmentId());
    }

    private static List<String> withCode(List<String> codes, String code) {
        var out = new ArrayList<>(codes);
        if (!out.contains(code)) {
            out.add(code);
        }
        return out;
    }

    private DocumentReviewWorkspace attachEligible(DocumentReviewWorkspace ws, DocumentReviewContext ctx) {
        var m = new Mutable(ws);
        var byKey = new HashMap<String, Integer>();
        for (var i = 0; i < m.groups.size(); i++) {
            byKey.put(m.groups.get(i).groupingKey(), i);
        }
        for (var di = 0; di < m.documents.size(); di++) {
            var doc = m.documents.get(di);
            var profile = options.profile(doc.documentType());
            if (doc.state() != ReviewDocumentState.READY || doc.groupId() != null || doc.clientId() == null
                    || doc.period().kind() == DocumentPeriodKind.UNKNOWN || profile == null) {
                continue;
            }
            var base = groupingKey(doc, profile);
            var key = profile.groupIndividually() ? base + "|document:" + hex(doc.id()) : base;
            var gi = byKey.get(key);
            UUID groupId;
            var now = clock.nowUtc();
            if (gi == null) {
                groupId = UUID.randomUUID();
                m.groups.add(new DocumentDispatchGroup(groupId, key, profile.groupingPolicyCode(), profile.version(),
                        doc.clientId(), profile.includeEstablishmentInGrouping() ? doc.establishmentId() : null,
                        Objects.requireNonNullElse(doc.clientDisplayName(), "Cliente resolvido"),
                        doc.period().groupingPeriodKey(), doc.period().groupingLabel(),
                        ReviewGroupState.READY_FOR_REVIEW, 1, List.of(doc.id()), List.of(), null, now, now));
                byKey.put(key, m.groups.size() - 1);
            } else {
                invalidateApproval(m, gi, ctx, "Novo documento adicionado ao grupo.");
                var g = m.groups.get(gi);
                groupId = g.id();
                var ids = new ArrayList<>(g.documentIds());
                if (!ids.contains(doc.id())) {
                    ids.add(doc.id());
                }
                m.groups.set(gi, g.toBuilder().clientDisplayName(Objects.requireNonNullElse(doc.clientDisplayName(),
                        g.clientDisplayName())).periodLabel(doc.period().groupingLabel()).documentIds(ids)
                        .revision(g.revision() + 1).updatedAtUtc(now).build());
            }
            m.documents.set(di, doc.toBuilder().groupId(groupId).state(ReviewDocumentState.GROUPED).build());
            m.audits.add(audit(ctx, "document.grouped", doc.id(), groupId, null, profile.groupingPolicyCode(), null));
        }
        return m.build();
    }

    private DocumentReviewWorkspace invalidateChangedSnapshots(DocumentReviewWorkspace ws, DocumentReviewContext ctx) {
        var m = new Mutable(ws);
        for (var i = 0; i < m.groups.size(); i++) {
            var g = m.groups.get(i);
            if (g.approvalSnapshot() == null) {
                continue;
            }
            if (!contentHash(g, ws.documents()).equals(g.approvalSnapshot().contentHash())
                    || g.approvalSnapshot().groupRevision() != g.revision()) {
                invalidateApproval(m, i, ctx, "Conteúdo, validação ou composição mudou após a aprovação.");
            }
        }
        return m.build();
    }

    private DocumentReviewWorkspace pruneEmptyGroups(DocumentReviewWorkspace ws, DocumentReviewContext ctx,
            String reason) {
        var docIds = ws.documents().stream().map(ReviewDocument::id).collect(Collectors.toSet());
        var linked = ws.documents().stream().map(ReviewDocument::groupId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var empty = ws.groups().stream().filter(g -> !linked.contains(g.id())
                && g.documentIds().stream().noneMatch(docIds::contains)).map(DocumentDispatchGroup::id).toList();
        if (empty.isEmpty()) {
            return ws;
        }
        var m = new Mutable(ws);
        for (var id : empty) {
            var i = m.groupIndex(id);
            invalidateApproval(m, i, ctx, reason);
            var g = m.groups.get(i);
            m.audits.add(audit(ctx, "group.empty_removed", null, g.id(), "state:" + g.state() + ";revision:"
                    + g.revision() + ";documents:" + g.documentIds().size(), null, reason));
            m.groups.remove(i);
        }
        return m.build();
    }

    static DocumentReviewWorkspace recalculateGroups(DocumentReviewWorkspace ws) {
        var byId = ws.documents().stream().collect(Collectors.toMap(ReviewDocument::id, d -> d, (a, b) -> a));
        var groups = ws.groups().stream().map(g -> {
            var members = g.documentIds().stream().map(byId::get).filter(Objects::nonNull).toList();
            var findings = groupFindings(g, members);
            ReviewGroupState state;
            if (g.approvalSnapshot() != null && findings.stream().noneMatch(ValidationFinding::preventsApproval)) {
                state = ReviewGroupState.APPROVED;
            } else if (members.isEmpty()) {
                state = ReviewGroupState.BUILDING;
            } else if (findings.stream().anyMatch(ValidationFinding::preventsApproval)
                    || members.stream().anyMatch(ReviewDocument::preventsApproval)) {
                state = ReviewGroupState.BLOCKED;
            } else {
                state = ReviewGroupState.READY_FOR_REVIEW;
            }
            // Pendência 2.8: rótulo sempre derivado da competência do grupo.
            var label = members.isEmpty() ? g.periodLabel() : members.getFirst().period().groupingLabel();
            return g.toBuilder().findings(findings).state(state).periodLabel(label).build();
        }).toList();
        var approved = groups.stream().filter(DocumentDispatchGroup::isApproved)
                .flatMap(g -> g.documentIds().stream()).collect(Collectors.toSet());
        var docs = ws.documents().stream().map(d -> {
            if (d.state() == ReviewDocumentState.BLOCKED || d.state() == ReviewDocumentState.DUPLICATE) {
                return d;
            }
            var state = approved.contains(d.id()) ? ReviewDocumentState.APPROVED
                    : d.groupId() != null ? ReviewDocumentState.GROUPED : ReviewDocumentState.READY;
            return d.toBuilder().state(state).build();
        }).toList();
        return ws.toBuilder().documents(docs).groups(groups).build();
    }

    private DocumentReviewWorkspace approveGroup(DocumentReviewWorkspace ws, UUID groupId, DocumentReviewContext ctx) {
        var group = approvable(ws, groupId);
        var byId = ws.documents().stream().collect(Collectors.toMap(ReviewDocument::id, d -> d));
        var members = group.documentIds().stream().map(byId::get).sorted(Comparator.comparing(ReviewDocument::id))
                .toList();
        var snapshotDocs = members.stream().map(d -> new ApprovalDocumentSnapshot(d.id(), d.sha256(), d.revision(),
                d.clientId(), d.establishmentId(), d.period().canonicalKey(), d.semanticDuplicateKey())).toList();
        var now = clock.nowUtc();
        var snapshot = new GroupApprovalSnapshot(UUID.randomUUID(), group.id(), group.revision(),
                contentHash(group, ws.documents()), ctx.actorId(), now, snapshotDocs);
        var m = new Mutable(ws);
        m.groups.set(m.groupIndex(groupId), group.toBuilder().state(ReviewGroupState.APPROVED)
                .approvalSnapshot(snapshot).updatedAtUtc(now).build());
        m.audits.add(audit(ctx, "group.approved", null, group.id(), null, snapshot.contentHash(),
                "Conteúdo e agrupamento registrados; esta aprovação não envia e-mail."));
        return recalculateGroups(m.build());
    }

    private static DocumentReviewWorkspace updatedRef(DocumentReviewWorkspace ws) {
        return ws;
    }

    static DocumentDispatchGroup approvable(DocumentReviewWorkspace ws, UUID groupId) {
        var group = ws.groups().stream().filter(g -> g.id().equals(groupId)).findFirst()
                .orElseThrow(() -> new DocumentReviewException("group.not_found", "O grupo selecionado não existe."));
        if (!isApprovable(ws, group)) {
            throw new DocumentReviewException("group.approval_blocked",
                    "O grupo contém erro, bloqueio ou duplicidade e não pode ser aprovado.");
        }
        return group;
    }

    static boolean isApprovable(DocumentReviewWorkspace ws, DocumentDispatchGroup group) {
        var ids = Set.copyOf(group.documentIds());
        var members = ws.documents().stream().filter(d -> ids.contains(d.id())).toList();
        return group.state() == ReviewGroupState.READY_FOR_REVIEW && !group.preventsApproval() && !members.isEmpty()
                && members.stream().allMatch(d -> !d.preventsApproval() && d.state() != ReviewDocumentState.DUPLICATE);
    }

    private static String competenceKey(DocumentDispatchGroup group, DocumentReviewWorkspace ws) {
        var ids = Set.copyOf(group.documentIds());
        var keys = ws.documents().stream().filter(d -> ids.contains(d.id())).map(d -> {
            var p = d.period();
            var year = p.year() != null ? p.year() : p.startDate() != null ? Integer.valueOf(p.startDate().getYear()) : null;
            var month = p.month() != null ? p.month()
                    : p.startDate() != null ? Integer.valueOf(p.startDate().getMonthValue()) : null;
            return year != null && month != null ? "month:%04d-%02d".formatted(year, month) : p.canonicalKey();
        }).distinct().toList();
        return keys.size() == 1 ? keys.getFirst() : group.periodKey();
    }

    private static List<ValidationFinding> groupFindings(DocumentDispatchGroup g, List<ReviewDocument> members) {
        var out = new ArrayList<ValidationFinding>();
        var now = g.updatedAtUtc();
        if (members.stream().map(ReviewDocument::clientId).distinct().count() > 1
                || members.stream().anyMatch(d -> !g.clientId().equals(d.clientId()))) {
            out.add(new ValidationFinding(UUID.nameUUIDFromBytes(("gcm" + g.id()).getBytes(StandardCharsets.UTF_8)),
                    "group.client_mismatch", ValidationSeverity.BLOCKER,
                    "O grupo contém documentos de clientes diferentes.", "ClientId", false, FindingResolutionType.NONE,
                    null, null, now, null));
        }
        if (members.stream().map(d -> d.period().groupingPeriodKey()).distinct().count() > 1) {
            out.add(new ValidationFinding(UUID.nameUUIDFromBytes(("gpm" + g.id()).getBytes(StandardCharsets.UTF_8)),
                    "group.period_mismatch", ValidationSeverity.BLOCKER,
                    "O grupo contém períodos contábeis incompatíveis.", "Period", false, FindingResolutionType.NONE,
                    null, null, now, null));
        }
        return out;
    }

    private static void applyDuplicates(List<ReviewDocument> docs, java.time.OffsetDateTime now) {
        var exact = new HashMap<String, UUID>();
        var semantic = new HashMap<String, UUID>();
        var order = new ArrayList<Integer>();
        for (var i = 0; i < docs.size(); i++) {
            order.add(i);
        }
        // Desempate pela ordem de importação (posição na lista), não por id aleatório.
        order.sort(Comparator.<Integer, java.time.OffsetDateTime>comparing(i -> docs.get(i).importedAtUtc())
                .thenComparing(Comparator.naturalOrder()));
        for (var i : order) {
            var d = docs.get(i);
            var sha = d.sha256().toUpperCase(Locale.ROOT);
            var original = exact.get(sha);
            if (original != null) {
                docs.set(i, markDuplicate(d, "duplicate.exact_sha256",
                        "O mesmo conteúdo já foi importado neste espaço; nenhum novo grupo será criado.", original, now));
                continue;
            }
            exact.put(sha, d.id());
            if (!d.semanticDuplicateKey().isEmpty()) {
                original = semantic.putIfAbsent(d.semanticDuplicateKey(), d.id());
                if (original != null) {
                    docs.set(i, markDuplicate(d, "duplicate.semantic_key",
                            "Outro PDF representa a mesma obrigação contábil configurada; revise o original.", original,
                            now));
                }
            }
        }
    }

    private static ReviewDocument markDuplicate(ReviewDocument d, String code, String message, UUID original,
            java.time.OffsetDateTime now) {
        var findings = new ArrayList<>(d.findings());
        findings.add(new ValidationFinding(UUID.randomUUID(), code, ValidationSeverity.BLOCKER,
                message + " Referência local: " + hex(original) + ".", "SHA256", false, FindingResolutionType.NONE,
                null, null, now, null));
        return d.toBuilder().findings(preserveHistory(d.findings(), findings)).state(ReviewDocumentState.DUPLICATE)
                .groupId(null).build();
    }

    private static String semanticKey(ReviewDocument d, DocumentReviewProfile profile) {
        if (d.clientId() == null || d.period().kind() == DocumentPeriodKind.UNKNOWN || profile == null) {
            return "";
        }
        var parts = new ArrayList<String>(List.of(hex(d.clientId()), d.documentType().name(),
                d.period().canonicalKey()));
        for (var role : profile.semanticIdentityRoles()) {
            // Pendência 2.9: todos os valores do papel (ex.: todos os CPFs no 13º), ordenados.
            var values = d.fields().stream().filter(f -> f.role() == role).map(f -> f.value().strip()
                    .toUpperCase(Locale.ROOT)).distinct().sorted().collect(Collectors.joining(","));
            parts.add(values.isEmpty() ? "-" : values);
        }
        return String.join("|", parts);
    }

    private static String groupingKey(ReviewDocument d, DocumentReviewProfile profile) {
        var establishment = profile.includeEstablishmentInGrouping() && d.establishmentId() != null
                ? hex(d.establishmentId()) : "client-root";
        return String.join("|", hex(d.clientId()), establishment, d.period().groupingPeriodKey(),
                profile.groupingPolicyCode(), profile.version());
    }

    static String contentHash(DocumentDispatchGroup g, List<ReviewDocument> docs) {
        var b = new StringBuilder().append(hex(g.id())).append('|').append(g.revision()).append('|')
                .append(hex(g.clientId())).append('|')
                .append(g.establishmentId() == null ? "-" : hex(g.establishmentId())).append('|')
                .append(g.periodKey()).append('|').append(g.groupingPolicyCode()).append('|')
                .append(g.groupingPolicyVersion());
        var ids = Set.copyOf(g.documentIds());
        docs.stream().filter(d -> ids.contains(d.id())).sorted(Comparator.comparing(ReviewDocument::id)).forEach(d ->
                b.append('\n').append(hex(d.id())).append('|').append(d.sha256()).append('|').append(d.revision())
                        .append('|').append(d.clientId() == null ? "-" : hex(d.clientId())).append('|')
                        .append(d.establishmentId() == null ? "-" : hex(d.establishmentId())).append('|')
                        .append(d.period().canonicalKey()).append('|').append(d.semanticDuplicateKey()));
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<ValidationFinding> preserveHistory(List<ValidationFinding> previous, List<ValidationFinding> current) {
        return current.stream().map(f -> previous.stream().filter(p -> p.ruleCode().equals(f.ruleCode())
                && p.severity() == f.severity() && p.message().equals(f.message())
                && Objects.equals(p.fieldKey(), f.fieldKey())).findFirst()
                .map(p -> f.toBuilder().id(p.id()).isResolved(p.isResolved()).resolutionType(p.resolutionType())
                        .resolvedBy(p.resolvedBy()).resolutionNote(p.resolutionNote()).createdAtUtc(p.createdAtUtc())
                        .resolvedAtUtc(p.resolvedAtUtc()).build())
                .orElse(f))
                .sorted(Comparator.comparing(ValidationFinding::severity).reversed()
                        .thenComparing(ValidationFinding::ruleCode))
                .toList();
    }

    private static boolean reviewChanged(ReviewDocument a, ReviewDocument b) {
        return ineligible(a.state()) != ineligible(b.state()) || !Objects.equals(a.clientId(), b.clientId())
                || !Objects.equals(a.establishmentId(), b.establishmentId())
                || !Objects.equals(a.clientDisplayName(), b.clientDisplayName())
                || !Objects.equals(a.clientTaxIdMasked(), b.clientTaxIdMasked())
                || a.resolutionMethod() != b.resolutionMethod()
                || a.resolutionConfidence().compareTo(b.resolutionConfidence()) != 0
                || !a.resolutionBlockers().equals(b.resolutionBlockers()) || !a.period().equals(b.period())
                || !Objects.equals(a.pendingClientChangeFrom(), b.pendingClientChangeFrom())
                || !a.semanticDuplicateKey().equals(b.semanticDuplicateKey()) || !findingsEqual(a.findings(), b.findings());
    }

    private static boolean ineligible(ReviewDocumentState s) {
        return s == ReviewDocumentState.BLOCKED || s == ReviewDocumentState.DUPLICATE;
    }

    private static boolean findingsEqual(List<ValidationFinding> a, List<ValidationFinding> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            if (!x.ruleCode().equals(y.ruleCode()) || x.severity() != y.severity() || !x.message().equals(y.message())
                    || !Objects.equals(x.fieldKey(), y.fieldKey()) || x.isResolved() != y.isResolved()) {
                return false;
            }
        }
        return true;
    }

    private void detachChangedIdentity(List<ReviewDocument> previous, Mutable m, DocumentReviewContext ctx) {
        var current = m.documents.stream().collect(Collectors.toMap(ReviewDocument::id, d -> d));
        for (var p : previous) {
            if (p.groupId() == null) {
                continue;
            }
            var c = current.get(p.id());
            if (Objects.equals(p.clientId(), c.clientId()) && Objects.equals(p.establishmentId(), c.establishmentId())
                    && Objects.equals(p.clientDisplayName(), c.clientDisplayName())
                    && p.period().groupingPeriodKey().equals(c.period().groupingPeriodKey())
                    && p.semanticDuplicateKey().equals(c.semanticDuplicateKey())) {
                continue;
            }
            detach(m, ctx, p.id(), p.groupId(), "Cliente, competência ou identidade contábil alterada após revalidação.");
        }
    }

    private void detachIneligible(Mutable m, DocumentReviewContext ctx) {
        for (var d : List.copyOf(m.documents)) {
            if (d.groupId() != null && ineligible(d.state())) {
                detach(m, ctx, d.id(), d.groupId(), "Documento deixou de ser elegível após revalidação.");
            }
        }
    }

    private void detach(Mutable m, DocumentReviewContext ctx, UUID documentId, UUID groupId, String reason) {
        var gi = m.findGroup(groupId);
        if (gi >= 0) {
            invalidateApproval(m, gi, ctx, reason);
            var g = m.groups.get(gi);
            m.groups.set(gi, g.toBuilder().documentIds(g.documentIds().stream().filter(id -> !id.equals(documentId))
                    .toList()).revision(g.revision() + 1).updatedAtUtc(clock.nowUtc()).build());
        }
        for (var i = 0; i < m.documents.size(); i++) {
            if (m.documents.get(i).id().equals(documentId)) {
                m.documents.set(i, m.documents.get(i).toBuilder().groupId(null).build());
            }
        }
    }

    private void invalidateApproval(Mutable m, int index, DocumentReviewContext ctx, String reason) {
        var g = m.groups.get(index);
        if (g.approvalSnapshot() == null) {
            return;
        }
        m.groups.set(index, g.toBuilder().approvalSnapshot(null).state(ReviewGroupState.READY_FOR_REVIEW).build());
        m.audits.add(audit(ctx, "group.approval_invalidated", null, g.id(), g.approvalSnapshot().contentHash(), null,
                reason));
    }

    private ReviewAuditEvent audit(DocumentReviewContext ctx, String action, UUID documentId, UUID groupId,
            String previous, String current, String reason) {
        return new ReviewAuditEvent(UUID.randomUUID(), ctx.scopeKey(), ctx.actorId(), clock.nowUtc(), action,
                documentId, groupId, previous, current, reason, hex(UUID.randomUUID()));
    }

    private static DocumentReviewWorkspace withAudit(DocumentReviewWorkspace ws, ReviewAuditEvent event) {
        var audits = new ArrayList<>(ws.auditEvents());
        audits.add(event);
        return ws.toBuilder().auditEvents(audits).build();
    }

    static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? "Cliente não identificado"
                : value.strip().replace(';', ',').replace('\r', ' ').replace('\n', ' ');
    }

    private void validateReason(String reason, String code) {
        if (reason == null || reason.strip().length() < options.minimumOverrideReasonLength()) {
            throw new DocumentReviewException(code, "Informe uma justificativa com ao menos "
                    + options.minimumOverrideReasonLength() + " caracteres.");
        }
    }

    static void validateCorrectedPeriod(DocumentPeriod p) {
        var valid = switch (p.kind()) {
            case MONTHLY -> p.month() != null && p.month() >= 1 && p.month() <= 12 && p.year() != null
                    && p.year() >= 1900 && p.year() <= 9999;
            case ANNUAL -> p.month() == null && p.year() != null && p.year() >= 1900 && p.year() <= 9999;
            case DATE_RANGE -> p.startDate() != null && p.endDate() != null && !p.endDate().isBefore(p.startDate());
            case EVENT_DATE -> p.startDate() != null;
            case ASSESSMENT_PERIOD -> p.startDate() != null && (p.endDate() == null || !p.endDate().isBefore(p.startDate()));
            case UNKNOWN -> false;
        };
        if (!valid) {
            throw new DocumentReviewException("period.correction_invalid",
                    "Informe uma competência ou período contábil válido para corrigir o documento.");
        }
    }

    private static boolean periodsEquivalent(DocumentPeriod a, DocumentPeriod b) {
        return a.kind() == b.kind() && Objects.equals(a.month(), b.month()) && Objects.equals(a.year(), b.year())
                && Objects.equals(a.startDate(), b.startDate()) && Objects.equals(a.endDate(), b.endDate())
                && Objects.equals(a.dueDate(), b.dueDate());
    }

    private DocumentReviewWorkspace execute(CancellationToken ct,
            BiFunction<DocumentReviewWorkspace, DocumentReviewContext, DocumentReviewWorkspace> operation) {
        gate.lock();
        try {
            ct.throwIfCancellationRequested();
            var ctx = contextAccessor.current();
            if (ctx == null || ctx.scopeKey() == null || ctx.scopeKey().isBlank() || ctx.actorId() == null
                    || ctx.actorId().isBlank()) {
                throw new DocumentReviewException("review.context_invalid",
                        "O contexto autenticado de revisão não está disponível.");
            }
            var ws = store.load(ctx.scopeKey());
            var updated = operation.apply(ws, ctx);
            if (!ctx.scopeKey().equals(updated.scopeKey())) {
                throw new DocumentReviewException("review.scope_mismatch",
                        "O espaço de revisão não pertence ao contexto autenticado atual.");
            }
            ct.throwIfCancellationRequested();
            return store.save(updated, ws.version());
        } finally {
            gate.unlock();
        }
    }

    /** Listas mutáveis de trabalho sobre um workspace imutável. */
    private static final class Mutable {
        final DocumentReviewWorkspace base;
        final List<ReviewDocument> documents;
        final List<DocumentDispatchGroup> groups;
        final List<ReviewAuditEvent> audits;

        Mutable(DocumentReviewWorkspace ws) {
            base = ws;
            documents = new ArrayList<>(ws.documents());
            groups = new ArrayList<>(ws.groups());
            audits = new ArrayList<>(ws.auditEvents());
        }

        int documentIndex(UUID id) {
            for (var i = 0; i < documents.size(); i++) {
                if (documents.get(i).id().equals(id)) {
                    return i;
                }
            }
            throw new DocumentReviewException("document.not_found",
                    "O documento selecionado não existe neste espaço de revisão.");
        }

        int findGroup(UUID id) {
            for (var i = 0; i < groups.size(); i++) {
                if (groups.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        int groupIndex(UUID id) {
            var i = findGroup(id);
            if (i < 0) {
                throw new DocumentReviewException("group.not_found", "O grupo selecionado não existe.");
            }
            return i;
        }

        DocumentReviewWorkspace build() {
            return base.toBuilder().documents(documents).groups(groups).auditEvents(audits).build();
        }
    }
}
