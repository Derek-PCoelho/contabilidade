package br.com.contadoresassociados.folhas.infrastructure.reports;

import static br.com.contadoresassociados.folhas.infrastructure.reports.ReportText.row;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresenter;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchReportFilter;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchBlock;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingBatch;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationFinding;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Linhas tabulares dos relatórios (as mesmas colunas e ordem da versão .NET). */
final class ReportRows {

    static final String TIMESTAMP = "Data e hora (UTC)";
    static final String STATUS = "Situação";
    static final Collator PT = Collator.getInstance(Locale.forLanguageTag("pt-BR"));

    static {
        PT.setStrength(Collator.SECONDARY);
    }

    static final String[] ITEM_HEADERS = {TIMESTAMP, "Operador", "Cliente", "Estabelecimento", "Documento",
            "Tipo de documento", "Competência / período", "Destinatário", "Operação", STATUS, "Entrega ao destinatário",
            "Evidência disponível", "Próxima ação", "Nível de atenção", "Serviço de e-mail", "Observação",
            "Versão do aplicativo", "ID interno do lote", "ID interno do item", "ID interno do grupo",
            "ID interno do estabelecimento", "Hash abreviado", "Destinatário original", "ID da mensagem no serviço",
            "ID do rascunho no serviço", "Código técnico"};
    static final String[] ERROR_HEADERS = {TIMESTAMP, "Área", "Descrição", STATUS, "Código técnico",
            "ID interno do lote", "ID interno do item", "ID interno do grupo"};
    static final String[] DUPLICATE_HEADERS = {"Arquivo", "Cliente", "Tipo de documento", "Competência / período",
            STATUS, "Hash abreviado", "Chave técnica de repetição", "ID interno do documento"};
    static final String[] AUDIT_HEADERS = {TIMESTAMP, "Área", "Operador", "Ação", "Resultado", "Detalhes",
            "Código técnico", "ID interno do lote", "ID interno do item", "ID interno do documento",
            "ID interno do grupo", "Correlação técnica", "Valor anterior técnico", "Valor novo técnico"};

    private ReportRows() {
    }

    static String iso(OffsetDateTime t) {
        return t == null ? "" : t.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String id(UUID id) {
        return id == null ? "" : id.toString();
    }

    static DeliveryAttempt latestAttempt(DispatchWorkspace ws, UUID itemId) {
        return ws.attempts().stream().filter(a -> a.dispatchItemId().equals(itemId))
                .max(Comparator.comparingInt(DeliveryAttempt::attemptNumber)).orElse(null);
    }

    static ValidationSeverity maxSeverity(List<DispatchBlock> blocks) {
        return blocks.stream().map(DispatchBlock::severity).max(Comparator.naturalOrder()).orElse(ValidationSeverity.INFO);
    }

    static List<String[]> items(DispatchWorkspace dispatch, DocumentReviewWorkspace review) {
        var rows = new ArrayList<String[]>();
        rows.add(ITEM_HEADERS);
        var attached = new HashSet<UUID>();
        dispatch.items().forEach(i -> {
            if (i.message() != null) {
                i.message().attachments().forEach(a -> attached.add(a.documentId()));
            }
        });
        var byId = new HashMap<UUID, ReviewDocument>();
        review.documents().forEach(d -> byId.put(d.id(), d));
        dispatch.items().stream().sorted(Comparator.comparing(DispatchItem::createdAtUtc,
                Comparator.nullsFirst(Comparator.naturalOrder()))).forEach(item -> {
                    var batch = dispatch.batches().stream().filter(b -> b.id().equals(item.batchId())).findFirst()
                            .orElse(null);
                    var attempt = latestAttempt(dispatch, item.id());
                    var attachments = item.message() == null ? List.<DispatchAttachmentSnapshot>of()
                            : item.message().attachments();
                    if (attachments.isEmpty()) {
                        rows.add(itemRow(item, batch, attempt, null, null));
                    } else {
                        attachments.forEach(a -> rows.add(itemRow(item, batch, attempt, byId.get(a.documentId()), a)));
                    }
                });
        review.documents().stream().filter(d -> !attached.contains(d.id()))
                .sorted(Comparator.comparing(ReviewDocument::importedAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(d -> Objects.requireNonNullElse(d.clientDisplayName(), ""), PT)
                        .thenComparing(ReviewDocument::fileName, PT))
                .forEach(d -> rows.add(unprepared(d)));
        return rows;
    }

    private static String[] unprepared(ReviewDocument d) {
        var findings = d.findings().stream().filter(f -> !f.isResolved()).toList();
        var severity = findings.stream().map(ValidationFinding::severity).max(Comparator.naturalOrder())
                .orElse(ValidationSeverity.INFO);
        var next = switch (d.state()) {
            case APPROVED -> "Preparar e conferir a mensagem deste cliente.";
            case READY, GROUPED -> "Conferir e liberar os documentos deste cliente.";
            case BLOCKED -> "Corrigir as pendências indicadas em Documentos.";
            case DUPLICATE -> "Manter fora da aprovação ou retirar o arquivo repetido.";
        };
        return row(iso(d.validatedAtUtc()), "Aplicativo",
                d.clientDisplayName() == null ? "Cliente não identificado" : d.clientDisplayName(),
                d.establishmentId() == null ? "Matriz / geral" : "Filial / estabelecimento", d.fileName(),
                ReportText.documentType(d.documentType()), d.period() == null ? "" : d.period().displayLabel(), "",
                "Documento ainda sem comunicação",
                d.state() == ReviewDocumentState.APPROVED ? "Nenhuma mensagem preparada" : ReportText.review(d.state()),
                "Não houve envio", "Situação documental registrada somente neste aplicativo", next,
                ReportText.severity(severity), "Serviço ainda não definido",
                findings.stream().map(ValidationFinding::message).collect(Collectors.joining("; ")),
                AppVersion.CURRENT.toString(), "", "", id(d.groupId()), id(d.establishmentId()),
                ReportText.shortHash(d.sha256()), "", "", "",
                findings.stream().map(ValidationFinding::ruleCode).collect(Collectors.joining("; ")));
    }

    private static String[] itemRow(DispatchItem item, ProcessingBatch batch, DeliveryAttempt attempt, ReviewDocument doc,
            DispatchAttachmentSnapshot att) {
        var establishment = doc != null && doc.establishmentId() != null ? doc.establishmentId() : item.establishmentId();
        RecognizedDocumentType type = doc != null ? doc.documentType() : att == null ? null : att.documentType();
        var hash = doc != null ? doc.sha256() : att == null ? null : att.sha256();
        var errorCode = attempt != null && attempt.errorCode() != null ? attempt.errorCode()
                : item.blocks().stream().map(DispatchBlock::code).collect(Collectors.joining("; "));
        var observation = attempt == null ? null : attempt.redactedError();
        if (observation == null || observation.isBlank()) {
            observation = item.blocks().stream().map(DispatchBlock::message).collect(Collectors.joining("; "));
        }
        if (observation.isBlank() && !errorCode.isBlank()) {
            observation = ReportText.error(errorCode);
        }
        var p = DispatchOutcomePresenter.present(item, attempt);
        var message = item.message();
        return row(iso(attempt != null && attempt.completedAtUtc() != null ? attempt.completedAtUtc() : item.updatedAtUtc()),
                ReportText.actor(batch == null ? null : batch.createdBy()), item.clientDisplayName(),
                establishment == null ? "Matriz / geral" : "Filial / estabelecimento",
                doc != null ? doc.fileName() : att == null ? "" : att.fileName(),
                type == null ? "" : ReportText.documentType(type), item.periodLabel(),
                message == null ? "" : String.join("; ", message.effectiveTo()), ReportText.mode(item.mode()),
                p.operationResult(), p.deliveryStatus(), p.evidence(), p.nextAction(),
                ReportText.severity(maxSeverity(item.blocks())),
                ReportText.provider(attempt == null ? null : attempt.providerKey(),
                        message == null ? null : message.senderAccountId()),
                observation, AppVersion.CURRENT.toString(), id(item.batchId()), id(item.id()), id(item.groupId()),
                id(establishment), ReportText.shortHash(hash),
                message == null ? "" : message.originalTo().stream().map(r -> r.email()).collect(Collectors.joining("; ")),
                attempt == null ? "" : attempt.providerMessageId(), attempt == null ? "" : attempt.providerDraftId(),
                errorCode);
    }

    static List<String[]> errors(DispatchWorkspace dispatch, DocumentReviewWorkspace review) {
        var body = new ArrayList<String[]>();
        dispatch.attempts().stream().filter(a -> a.errorCode() != null).forEach(a -> body.add(row(
                iso(a.completedAtUtc() != null ? a.completedAtUtc() : a.startedAtUtc()), "Mensagens e envios",
                a.redactedError() == null || a.redactedError().isBlank() ? ReportText.error(a.errorCode()) : a.redactedError(),
                ReportText.attempt(a.state()), a.errorCode(), id(a.batchId()), id(a.dispatchItemId()), id(a.groupId()))));
        dispatch.items().forEach(i -> i.blocks().forEach(b -> body.add(row(iso(i.updatedAtUtc()), "Preparação da mensagem",
                b.message(), ReportText.severity(b.severity()), b.code(), id(i.batchId()), id(i.id()), id(i.groupId())))));
        review.documents().forEach(d -> d.findings().stream().filter(f -> !f.isResolved()
                && (f.severity() == ValidationSeverity.ERROR || f.severity() == ValidationSeverity.BLOCKER))
                .forEach(f -> body.add(row(iso(d.validatedAtUtc()), "Documentos", d.fileName() + ": " + f.message(),
                        ReportText.severity(f.severity()), f.ruleCode(), "", "", id(d.groupId())))));
        return withHeader(ERROR_HEADERS, body);
    }

    static List<String[]> duplicates(DocumentReviewWorkspace review) {
        var rows = new ArrayList<String[]>();
        rows.add(DUPLICATE_HEADERS);
        review.documents().stream().filter(d -> d.state() == ReviewDocumentState.DUPLICATE)
                .sorted(Comparator.comparing((ReviewDocument d) -> Objects.requireNonNullElse(d.clientDisplayName(), ""), PT)
                        .thenComparing(d -> d.period() == null ? "" : d.period().displayLabel())
                        .thenComparing(ReviewDocument::fileName, PT))
                .forEach(d -> rows.add(row(d.fileName(),
                        d.clientDisplayName() == null ? "Cliente não identificado" : d.clientDisplayName(),
                        ReportText.documentType(d.documentType()), d.period() == null ? "" : d.period().displayLabel(),
                        ReportText.review(d.state()), ReportText.shortHash(d.sha256()), d.semanticDuplicateKey(),
                        id(d.id()))));
        return rows;
    }

    static List<String[]> audit(DispatchWorkspace dispatch, DocumentReviewWorkspace review) {
        var body = new ArrayList<String[]>();
        dispatch.auditEvents().forEach(e -> body.add(row(iso(e.timestampUtc()), "Mensagens e envios",
                ReportText.actor(e.actorId()), ReportText.action(e.action()), ReportText.outcome(e.outcome()),
                e.errorCode() == null || e.errorCode().isBlank() ? "" : ReportText.error(e.errorCode()), e.errorCode(),
                id(e.batchId()), id(e.dispatchItemId()), "", id(e.groupId()), e.correlationId(), "",
                ReportText.outcome(e.outcome()))));
        review.auditEvents().forEach(e -> body.add(row(iso(e.timestampUtc()), "Documentos", ReportText.actor(e.actorId()),
                ReportText.action(e.action()), ReportText.outcome(e.newValue()), e.reason(), "", "", "",
                id(e.documentId()), id(e.groupId()), e.correlationId(), ReportText.outcome(e.previousValue()),
                ReportText.outcome(e.newValue()))));
        return withHeader(AUDIT_HEADERS, body);
    }

    static List<String[]> summary(DispatchWorkspace dispatch, DocumentReviewWorkspace review, OffsetDateTime exportedAt,
            String coverage) {
        var ps = dispatch.items().stream().map(i -> DispatchOutcomePresenter.present(i, latestAttempt(dispatch, i.id())))
                .toList();
        var rows = new ArrayList<String[]>();
        rows.add(row("Indicador", "Valor"));
        rows.add(row("Competência / período", coverage));
        rows.add(row("Gerado em (UTC)", iso(exportedAt)));
        rows.add(row("Mensagens", Integer.toString(dispatch.items().size())));
        rows.add(row("Documentos", Integer.toString(inventory(dispatch, review).size())));
        rows.add(row("Simulações locais concluídas",
                Long.toString(ps.stream().filter(p -> p.simulation() && p.technicalSuccess()).count())));
        rows.add(row("Aceitas pelo serviço", Long.toString(ps.stream().filter(p -> !p.simulation()
                && p.operationResult().toLowerCase(Locale.ROOT).contains("aceit")).count())));
        rows.add(row("Rascunhos salvos", Long.toString(drafts(dispatch))));
        rows.add(row("Não concluídas",
                Long.toString(dispatch.items().stream().filter(i -> i.state() == DispatchItemState.FAILED).count())));
        rows.add(row("Precisam de atenção", Long.toString(dispatch.items().stream().filter(i -> switch (i.state()) {
            case BLOCKED, AMBIGUOUS, SENDING, DRAFT_CREATING -> true;
            default -> false;
        }).count())));
        rows.add(row("Documentos repetidos", Long.toString(review.documents().stream()
                .filter(d -> d.state() == ReviewDocumentState.DUPLICATE).count())));
        rows.add(row("Conjuntos para mensagem", Integer.toString(groupCount(dispatch, review))));
        rows.add(row("Escopo interno", dispatch.scopeKey()));
        return rows;
    }

    static long drafts(DispatchWorkspace dispatch) {
        return dispatch.items().stream().filter(i -> !DispatchOutcomePresenter.present(i, latestAttempt(dispatch, i.id()))
                .simulation() && i.mode() == DispatchOperationMode.DRAFT && i.state() == DispatchItemState.DRAFT_CREATED)
                .count();
    }

    static int groupCount(DispatchWorkspace dispatch, DocumentReviewWorkspace review) {
        var ids = new HashSet<UUID>();
        review.groups().forEach(g -> ids.add(g.id()));
        dispatch.items().forEach(i -> ids.add(i.groupId()));
        return ids.size();
    }

    record DocumentEntry(UUID documentId, UUID groupId, UUID clientId, String clientDisplayName, UUID establishmentId,
            String fileName, String sha256, RecognizedDocumentType documentType, String periodLabel,
            ReviewDocumentState reviewState, OffsetDateTime recordedAtUtc, boolean messageSnapshotOnly) {
    }

    static List<DocumentEntry> inventory(DispatchWorkspace dispatch, DocumentReviewWorkspace review) {
        var entries = new LinkedHashMap<UUID, DocumentEntry>();
        review.documents().forEach(d -> entries.put(d.id(), new DocumentEntry(d.id(), d.groupId(), d.clientId(),
                d.clientDisplayName() == null ? "Cliente não identificado" : d.clientDisplayName(), d.establishmentId(),
                d.fileName(), d.sha256(), d.documentType(), d.period() == null ? "" : d.period().displayLabel(), d.state(),
                d.validatedAtUtc(), false)));
        dispatch.items().forEach(i -> {
            if (i.message() != null) {
                i.message().attachments().forEach(a -> entries.putIfAbsent(a.documentId(), new DocumentEntry(a.documentId(),
                        i.groupId(), i.clientId(), i.clientDisplayName(), i.establishmentId(), a.fileName(), a.sha256(),
                        a.documentType(), i.periodLabel(), null, i.updatedAtUtc(), true)));
            }
        });
        return entries.values().stream().sorted(Comparator.comparing(DocumentEntry::clientDisplayName, PT)
                .thenComparing(DocumentEntry::periodLabel).thenComparing(DocumentEntry::fileName, PT)).toList();
    }

    static String coverage(DispatchWorkspace dispatch, DocumentReviewWorkspace review, DispatchReportFilter f) {
        var periodCoverage = switch (f.scope()) {
            case MONTH -> "competência %02d/%04d".formatted(f.month(), f.year());
            case YEAR -> "ano %04d".formatted(f.year());
            case RANGE -> "período de %02d/%04d a %02d/%04d".formatted(f.startMonth(), f.startYear(), f.endMonth(),
                    f.endYear());
            default -> "todos os períodos";
        };
        if (f.clientId() != null || f.scope() == DispatchReportFilter.Scope.CLIENT) {
            var client = f.clientDisplayName() != null ? f.clientDisplayName()
                    : !dispatch.items().isEmpty() ? dispatch.items().getFirst().clientDisplayName()
                    : !review.groups().isEmpty() ? review.groups().getFirst().clientDisplayName()
                    : !review.documents().isEmpty() ? review.documents().getFirst().clientDisplayName() : null;
            return "Cliente: " + (client == null ? "Cliente selecionado" : client) + " • " + periodCoverage;
        }
        if (f.scope() == DispatchReportFilter.Scope.ALL_PERIODS) {
            return "Todos os períodos e clientes";
        }
        return periodCoverage.substring(0, 1).toUpperCase(Locale.ROOT) + periodCoverage.substring(1) + " • todos os clientes";
    }

    private static List<String[]> withHeader(String[] header, List<String[]> body) {
        body.sort(Comparator.comparing(r -> r[0]));
        var rows = new ArrayList<String[]>(body.size() + 1);
        rows.add(header);
        rows.addAll(body);
        return rows;
    }
}
