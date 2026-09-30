package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingBatch;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriod;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewAuditEvent;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Recorte de workspaces para relatórios (porta fiel do .NET, com comparação de GUID independente de formato). */
public final class DispatchReportFiltering {

    private DispatchReportFiltering() {
    }

    public static DocumentReviewWorkspace filterReview(DocumentReviewWorkspace workspace, DispatchReportFilter filter) {
        if (filter.scope() == DispatchReportFilter.Scope.ALL_PERIODS && filter.clientId() == null) {
            return workspace;
        }
        List<ReviewDocument> documents = workspace.documents().stream()
                .filter(d -> clientMatches(d.clientId(), filter.clientId()) && periodMatches(d.period(), filter))
                .toList();
        Set<UUID> documentIds = documents.stream().map(ReviewDocument::id).collect(Collectors.toSet());
        List<DocumentDispatchGroup> groups = workspace.groups().stream()
                .filter(g -> clientMatches(g.clientId(), filter.clientId())
                        && (!filter.hasPeriod()
                        || g.documentIds().stream().anyMatch(documentIds::contains)
                        || periodLabelMatches(g.periodLabel(), filter)))
                .toList();
        Set<UUID> groupIds = groups.stream().map(DocumentDispatchGroup::id).collect(Collectors.toSet());
        List<ReviewAuditEvent> audit = workspace.auditEvents().stream()
                .filter(a -> (a.documentId() != null && documentIds.contains(a.documentId()))
                        || (a.groupId() != null && groupIds.contains(a.groupId()))
                        || aggregateReviewAuditMatches(a, filter))
                .toList();
        return workspace.toBuilder().documents(documents).groups(groups).auditEvents(audit).build();
    }

    public static DispatchWorkspace filterDispatch(DispatchWorkspace workspace, DocumentReviewWorkspace review,
            DispatchReportFilter filter) {
        Set<UUID> groupIds = review.groups().stream().map(DocumentDispatchGroup::id).collect(Collectors.toSet());
        boolean unfiltered = filter.scope() == DispatchReportFilter.Scope.ALL_PERIODS && filter.clientId() == null;
        List<DispatchItem> scoped = unfiltered ? workspace.items() : workspace.items().stream()
                .filter(i -> clientMatches(i.clientId(), filter.clientId())
                        && (!filter.hasPeriod() || groupIds.contains(i.groupId())
                        || periodLabelMatches(i.periodLabel(), filter)))
                .toList();
        Set<UUID> scopedItemIds = scoped.stream().map(DispatchItem::id).collect(Collectors.toSet());
        Set<UUID> scopedBatchIds = scoped.stream().map(DispatchItem::batchId).collect(Collectors.toSet());
        List<DispatchAuditEvent> audit = unfiltered ? workspace.auditEvents() : workspace.auditEvents().stream()
                .filter(a -> auditMatchesScope(a, scopedItemIds, groupIds, scopedBatchIds))
                .toList();

        List<DispatchItem> items = projectCurrentItems(scoped);
        Set<UUID> itemIds = items.stream().map(DispatchItem::id).collect(Collectors.toSet());
        Set<UUID> currentGroupIds = items.stream().map(DispatchItem::groupId).collect(Collectors.toSet());
        Set<UUID> batchIds = items.stream().map(DispatchItem::batchId).collect(Collectors.toSet());
        List<ProcessingBatch> batches = workspace.batches().stream()
                .filter(b -> batchIds.contains(b.id()))
                .map(b -> b.toBuilder()
                        .groupIds(b.groupIds().stream().filter(currentGroupIds::contains).distinct().toList())
                        .dispatchItemIds(b.dispatchItemIds().stream().filter(itemIds::contains).distinct().toList())
                        .build())
                .toList();
        var attempts = workspace.attempts().stream().filter(a -> itemIds.contains(a.dispatchItemId())).toList();
        return workspace.toBuilder().batches(batches).items(items).attempts(attempts).auditEvents(audit).build();
    }

    /** Mantém apenas a revisão vigente (não cancelada) de cada grupo. */
    public static List<DispatchItem> projectCurrentItems(Collection<DispatchItem> items) {
        Comparator<DispatchItem> order = Comparator.comparingLong(DispatchItem::revision)
                .thenComparing(DispatchItem::createdAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(DispatchItem::updatedAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(DispatchItem::id);
        Map<UUID, DispatchItem> current = new LinkedHashMap<>();
        for (DispatchItem item : items) {
            if (item.state() == DispatchItemState.CANCELLED) {
                continue;
            }
            current.merge(item.groupId(), item, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        return new ArrayList<>(current.values());
    }

    private static boolean auditMatchesScope(DispatchAuditEvent audit, Set<UUID> itemIds, Set<UUID> groupIds,
            Set<UUID> batchIds) {
        if (audit.dispatchItemId() != null) {
            return itemIds.contains(audit.dispatchItemId());
        }
        if (audit.groupId() != null) {
            return groupIds.contains(audit.groupId());
        }
        return audit.batchId() != null && batchIds.contains(audit.batchId());
    }

    static boolean aggregateReviewAuditMatches(ReviewAuditEvent audit, DispatchReportFilter filter) {
        if (!"groups.client_approved".equals(audit.action()) || audit.newValue() == null
                || audit.newValue().isBlank()) {
            return false;
        }
        String client = auditValue(audit.newValue(), "client");
        if (filter.clientId() != null && (client == null || !normalizeGuid(client).equals(
                normalizeGuid(filter.clientId().toString())))) {
            return false;
        }
        if (!filter.hasPeriod()) {
            return true;
        }
        String periods = Objects.requireNonNullElse(auditValue(audit.newValue(), "periods"), "");
        return Arrays.stream(periods.split("\\|")).map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(p -> periodLabelMatches(p, filter));
    }

    private static String normalizeGuid(String value) {
        return value.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    static String auditValue(String value, String key) {
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] kv = trimmed.split(":", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                return kv[1].trim();
            }
        }
        return null;
    }

    private static boolean clientMatches(UUID value, UUID expected) {
        return expected == null || expected.equals(value);
    }

    static boolean periodMatches(DocumentPeriod period, DispatchReportFilter filter) {
        if (period == null) {
            return !filter.hasPeriod();
        }
        Integer year = period.year() != null ? period.year()
                : period.startDate() != null ? Integer.valueOf(period.startDate().getYear()) : null;
        Integer month = period.month() != null ? period.month()
                : period.startDate() != null ? Integer.valueOf(period.startDate().getMonthValue()) : null;
        return filter.matches(year, month);
    }

    /** Extrai ano (1900–9999) e mês adjacente de rótulos como "03/2026", "2026-03" ou "Março/2026". */
    public static boolean periodLabelMatches(String label, DispatchReportFilter filter) {
        if (!filter.hasPeriod()) {
            return true;
        }
        if (label == null) {
            return false;
        }
        int[] numbers = Arrays.stream(label.split("[/\\-:_ ]+"))
                .filter(s -> !s.isEmpty())
                .mapToInt(DispatchReportFiltering::parseNonNegative)
                .filter(v -> v >= 0)
                .toArray();
        int yearIndex = -1;
        for (int i = 0; i < numbers.length; i++) {
            if (numbers[i] >= 1900 && numbers[i] <= 9999) {
                yearIndex = i;
                break;
            }
        }
        Integer year = yearIndex < 0 ? null : numbers[yearIndex];
        Integer month = null;
        if (yearIndex > 0 && numbers[yearIndex - 1] >= 1 && numbers[yearIndex - 1] <= 12) {
            month = numbers[yearIndex - 1];
        } else if (yearIndex >= 0 && yearIndex + 1 < numbers.length
                && numbers[yearIndex + 1] >= 1 && numbers[yearIndex + 1] <= 12) {
            month = numbers[yearIndex + 1];
        }
        return filter.matches(year, month);
    }

    private static int parseNonNegative(String s) {
        if (s.length() > 9 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        return Integer.parseInt(s);
    }
}
