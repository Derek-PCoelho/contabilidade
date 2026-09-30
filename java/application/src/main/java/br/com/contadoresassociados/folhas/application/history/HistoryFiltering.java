package br.com.contadoresassociados.folhas.application.history;

import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriod;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewAuditEvent;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Filtros do histórico (tempo, competência, cliente, documento e texto sem acento). */
public final class HistoryFiltering {

    private HistoryFiltering() {
    }

    public enum TimeScope { ALL, CALENDAR_DAY, CALENDAR_MONTH, CALENDAR_YEAR, CUSTOM_INTERVAL }

    public record TimeWindow(TimeScope scope, OffsetDateTime startUtc, OffsetDateTime endExclusiveUtc) {

        public static final TimeWindow ALL = new TimeWindow(TimeScope.ALL, null, null);

        public static TimeWindow forDay(LocalDate day, ZoneId zone) {
            return new TimeWindow(TimeScope.CALENDAR_DAY, toUtc(day.atStartOfDay(), zone),
                    toUtc(day.plusDays(1).atStartOfDay(), zone));
        }

        public static TimeWindow forMonth(int year, int month, ZoneId zone) {
            var first = LocalDate.of(year, month, 1);
            return new TimeWindow(TimeScope.CALENDAR_MONTH, toUtc(first.atStartOfDay(), zone),
                    toUtc(first.plusMonths(1).atStartOfDay(), zone));
        }

        public static TimeWindow forYear(int year, ZoneId zone) {
            var first = LocalDate.of(year, 1, 1);
            return new TimeWindow(TimeScope.CALENDAR_YEAR, toUtc(first.atStartOfDay(), zone),
                    toUtc(first.plusYears(1).atStartOfDay(), zone));
        }

        public static TimeWindow forLocalInterval(LocalDate startDate, LocalTime startTime, LocalDate endDate,
                LocalTime endTime, ZoneId zone) {
            return forUtcInterval(toUtc(startDate.atTime(startTime), zone), toUtc(endDate.atTime(endTime), zone));
        }

        public static TimeWindow forUtcInterval(OffsetDateTime start, OffsetDateTime endExclusive) {
            var s = start.withOffsetSameInstant(ZoneOffset.UTC);
            var e = endExclusive.withOffsetSameInstant(ZoneOffset.UTC);
            if (!e.isAfter(s)) {
                throw new IllegalArgumentException("O fim do intervalo deve ser posterior ao início.");
            }
            return new TimeWindow(TimeScope.CUSTOM_INTERVAL, s, e);
        }

        public boolean contains(OffsetDateTime timestamp) {
            if (scope == TimeScope.ALL) {
                return true;
            }
            return startUtc != null && endExclusiveUtc != null && !timestamp.isBefore(startUtc)
                    && timestamp.isBefore(endExclusiveUtc);
        }

        /** Horário inexistente (salto de horário de verão) é rejeitado; ambíguo usa o maior offset, como no .NET. */
        static OffsetDateTime toUtc(LocalDateTime local, ZoneId zone) {
            var offsets = zone.getRules().getValidOffsets(local);
            if (offsets.isEmpty()) {
                throw new IllegalArgumentException(
                        "O horário escolhido não existe no fuso local por causa da mudança de horário.");
            }
            var offset = offsets.stream().max(Comparator.comparingInt(ZoneOffset::getTotalSeconds)).orElseThrow();
            return OffsetDateTime.of(local, offset).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }

    public record Competence(int year, Integer month) {
        public boolean matches(Integer y, Integer m) {
            return (y == null || year == y) && (m == null || Objects.equals(month, m));
        }

        public static Optional<Competence> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            int[] numbers = Arrays.stream(value.split("[/\\-:_ ]+")).filter(s -> !s.isEmpty() && s.length() <= 9
                    && s.chars().allMatch(c -> c >= '0' && c <= '9')).mapToInt(Integer::parseInt).toArray();
            int yearIndex = -1;
            for (int i = 0; i < numbers.length; i++) {
                if (numbers[i] >= 1900 && numbers[i] <= 9999) {
                    yearIndex = i;
                    break;
                }
            }
            if (yearIndex < 0) {
                return Optional.empty();
            }
            Integer month = null;
            if (yearIndex > 0 && numbers[yearIndex - 1] >= 1 && numbers[yearIndex - 1] <= 12) {
                month = numbers[yearIndex - 1];
            } else if (yearIndex + 1 < numbers.length && numbers[yearIndex + 1] >= 1 && numbers[yearIndex + 1] <= 12) {
                month = numbers[yearIndex + 1];
            }
            return Optional.of(new Competence(numbers[yearIndex], month));
        }
    }

    public record DocumentReference(UUID documentId, RecognizedDocumentType documentType, String displayName) {
    }

    public record Entry(UUID eventId, OffsetDateTime timestampUtc, UUID clientId, String clientName,
            List<Competence> competences, List<DocumentReference> documents, String searchText) {
        public Entry {
            competences = List.copyOf(competences);
            documents = List.copyOf(documents);
        }
    }

    public record Criteria(TimeWindow timeWindow, Integer competenceYear, Integer competenceMonth, UUID clientId,
            UUID documentId, RecognizedDocumentType documentType, String searchText) {
        public static final Criteria ALL = new Criteria(TimeWindow.ALL, null, null, null, null, null, null);

        public Criteria {
            timeWindow = timeWindow == null ? TimeWindow.ALL : timeWindow;
        }
    }

    public static <T> List<T> apply(Collection<T> source, Function<T, Entry> projection, Criteria criteria) {
        var query = normalizeSearch(criteria.searchText());
        return source.stream().filter(item -> matches(projection.apply(item), criteria, query)).toList();
    }

    public static boolean matches(Entry entry, Criteria c) {
        return matches(entry, c, normalizeSearch(c.searchText()));
    }

    private static boolean matches(Entry entry, Criteria c, String query) {
        if (!c.timeWindow().contains(entry.timestampUtc())
                || c.clientId() != null && !c.clientId().equals(entry.clientId())) {
            return false;
        }
        if ((c.competenceYear() != null || c.competenceMonth() != null)
                && entry.competences().stream().noneMatch(k -> k.matches(c.competenceYear(), c.competenceMonth()))) {
            return false;
        }
        if (c.documentId() != null && entry.documents().stream().noneMatch(d -> c.documentId().equals(d.documentId()))) {
            return false;
        }
        if (c.documentType() != null && entry.documents().stream().noneMatch(d -> d.documentType() == c.documentType())) {
            return false;
        }
        if (query.isEmpty()) {
            return true;
        }
        var searchable = normalizeSearch(String.join(" ", nz(entry.clientName()), nz(entry.searchText()),
                entry.documents().stream().map(DocumentReference::displayName).collect(Collectors.joining(" "))));
        return Arrays.stream(query.split(" ")).filter(t -> !t.isBlank()).allMatch(searchable::contains);
    }

    /** Remove acentos e compara em maiúsculas independentes de localidade. */
    public static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        var sb = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
                .forEach(cp -> sb.appendCodePoint(Character.toUpperCase(cp)));
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Índice para projetar eventos de auditoria em entradas filtráveis. */
    public static final class ProjectionIndex {
        private final Map<UUID, ReviewDocument> documents;
        private final Map<UUID, DocumentDispatchGroup> groups;
        private final Map<UUID, DispatchItem> items;
        private final Map<UUID, String> clientNames;

        private ProjectionIndex(Map<UUID, ReviewDocument> documents, Map<UUID, DocumentDispatchGroup> groups,
                Map<UUID, DispatchItem> items, Map<UUID, String> clientNames) {
            this.documents = documents;
            this.groups = groups;
            this.items = items;
            this.clientNames = clientNames;
        }

        public static ProjectionIndex create(Collection<ReviewDocument> documents,
                Collection<DocumentDispatchGroup> groups, Collection<DispatchItem> items, Map<UUID, String> clientNames) {
            return new ProjectionIndex(index(documents, ReviewDocument::id), index(groups, DocumentDispatchGroup::id),
                    index(items, DispatchItem::id), clientNames == null ? Map.of() : Map.copyOf(clientNames));
        }

        private static <T> Map<UUID, T> index(Collection<T> values, Function<T, UUID> key) {
            var map = new HashMap<UUID, T>();
            values.forEach(v -> map.put(key.apply(v), v));
            return map;
        }

        public Entry project(ReviewAuditEvent audit) {
            var doc = audit.documentId() == null ? null : documents.get(audit.documentId());
            var group = group(audit.groupId() != null ? audit.groupId() : doc == null ? null : doc.groupId());
            List<ReviewDocument> related = audit.documentId() != null ? (doc == null ? List.of() : List.of(doc))
                    : groupDocuments(group);
            var clientId = firstNonNull(parseUuid(auditValue(audit, "client")), doc == null ? null : doc.clientId(),
                    group == null ? null : group.clientId());
            var clientName = firstNonNull(auditValue(audit, "client-name"), doc == null ? null : doc.clientDisplayName(),
                    group == null ? null : group.clientDisplayName(), clientName(clientId));
            var competences = new ArrayList<Competence>();
            related.forEach(d -> competences.addAll(competences(d.period())));
            if (audit.documentId() == null && competences.isEmpty() && group != null) {
                add(competences, group.periodKey());
                add(competences, group.periodLabel());
            }
            addAudit(competences, audit.previousValue());
            addAudit(competences, audit.newValue());
            var refs = new ArrayList<>(related.stream().map(ProjectionIndex::reference).toList());
            var typeText = auditValue(audit, "type");
            var type = parseType(typeText);
            if (refs.isEmpty() && (audit.documentId() != null || type != null)) {
                refs.add(new DocumentReference(audit.documentId(), type,
                        type != null ? DocumentPresentation.label(type) : "Documento histórico"));
            }
            return new Entry(audit.id(), audit.timestampUtc(), clientId, clientName, distinct(competences),
                    distinct(refs), join(audit.action(), audit.reason(), audit.previousValue(), audit.newValue()));
        }

        public Entry project(DispatchAuditEvent audit) {
            var item = audit.dispatchItemId() == null ? null : items.get(audit.dispatchItemId());
            var group = group(audit.groupId() != null ? audit.groupId() : item == null ? null : item.groupId());
            var clientId = firstNonNull(item == null ? null : item.clientId(), group == null ? null : group.clientId());
            var clientName = firstNonNull(item == null ? null : item.clientDisplayName(),
                    group == null ? null : group.clientDisplayName(), clientName(clientId));
            var related = groupDocuments(group);
            List<DocumentReference> refs = item != null && item.message() != null
                    ? item.message().attachments().stream().map(a -> new DocumentReference(a.documentId(),
                            a.documentType(), a.fileName())).toList()
                    : related.stream().map(ProjectionIndex::reference).toList();
            var competences = new ArrayList<Competence>();
            add(competences, item == null ? null : item.periodLabel());
            add(competences, group == null ? null : group.periodKey());
            add(competences, group == null ? null : group.periodLabel());
            related.forEach(d -> competences.addAll(competences(d.period())));
            return new Entry(audit.id(), audit.timestampUtc(), clientId, clientName, distinct(competences),
                    distinct(refs), join(audit.action(), audit.outcome(), audit.errorCode(), audit.correlationId()));
        }

        public Entry project(AuditEventModel audit, UUID clientId, String clientName) {
            var id = clientId != null ? clientId : parseUuid(audit.entityId());
            return new Entry(audit.id(), audit.timestampUtc(), id, clientName != null ? clientName : clientName(id),
                    List.of(), List.of(), join(audit.action(), audit.category(), audit.severity(),
                            audit.redactedDataJson()));
        }

        private DocumentDispatchGroup group(UUID id) {
            return id == null ? null : groups.get(id);
        }

        private List<ReviewDocument> groupDocuments(DocumentDispatchGroup g) {
            return g == null ? List.of() : g.documentIds().stream().map(documents::get).filter(Objects::nonNull).toList();
        }

        private String clientName(UUID id) {
            var name = id == null ? null : clientNames.get(id);
            return name == null || name.isBlank() ? "Cliente não disponível" : name;
        }

        private static DocumentReference reference(ReviewDocument d) {
            return new DocumentReference(d.id(), d.documentType(),
                    d.fileName() + " " + DocumentPresentation.label(d.documentType()));
        }

        private static List<Competence> competences(DocumentPeriod p) {
            if (p == null) {
                return List.of();
            }
            Integer y = p.year() != null ? p.year() : p.startDate() == null ? null : p.startDate().getYear();
            Integer m = p.month() != null ? p.month() : p.startDate() == null ? null : p.startDate().getMonthValue();
            if (y != null) {
                return List.of(new Competence(y, m));
            }
            return Competence.parse(p.originalText()).map(List::of).orElse(List.of());
        }

        private static void add(List<Competence> list, String value) {
            Competence.parse(value).ifPresent(list::add);
        }

        private static void addAudit(List<Competence> list, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            for (var key : List.of("period", "periods")) {
                auditValues(value, key).flatMap(v -> Arrays.stream(v.split("\\|"))).map(String::strip)
                        .filter(s -> !s.isEmpty()).forEach(v -> add(list, v));
            }
            add(list, value);
        }

        private static RecognizedDocumentType parseType(String text) {
            if (text == null) {
                return null;
            }
            var normalized = text.strip().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
            return Arrays.stream(RecognizedDocumentType.values()).filter(t -> t.name().equals(normalized)
                    || t.name().replace("_", "").equals(normalized.replace("_", ""))).findFirst().orElse(null);
        }

        private static String auditValue(ReviewAuditEvent a, String key) {
            return firstNonNull(auditValues(a.newValue(), key).findFirst().orElse(null),
                    auditValues(a.previousValue(), key).findFirst().orElse(null));
        }

        private static Stream<String> auditValues(String value, String key) {
            if (value == null) {
                return Stream.empty();
            }
            return Arrays.stream(value.split(";")).map(String::strip).filter(s -> !s.isEmpty())
                    .map(s -> s.split(":", 2)).filter(kv -> kv.length == 2 && kv[0].strip().equalsIgnoreCase(key))
                    .map(kv -> kv[1].strip());
        }

        private static UUID parseUuid(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            var v = value.strip();
            if (v.length() == 32 && v.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                v = v.substring(0, 8) + "-" + v.substring(8, 12) + "-" + v.substring(12, 16) + "-" + v.substring(16, 20)
                        + "-" + v.substring(20);
            }
            try {
                return UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @SafeVarargs
        private static <T> T firstNonNull(T... values) {
            for (var v : values) {
                if (v != null) {
                    return v;
                }
            }
            return null;
        }

        private static String join(String... parts) {
            return Arrays.stream(parts).filter(Objects::nonNull).collect(Collectors.joining(" "));
        }

        private static <T> List<T> distinct(List<T> values) {
            return List.copyOf(new LinkedHashSet<>(values));
        }
    }
}
