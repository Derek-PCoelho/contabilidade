package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.application.history.HistoryFiltering;
import br.com.contadoresassociados.folhas.application.history.HistoryFiltering.Criteria;
import br.com.contadoresassociados.folhas.application.history.HistoryFiltering.TimeScope;
import br.com.contadoresassociados.folhas.application.history.HistoryFiltering.TimeWindow;
import br.com.contadoresassociados.folhas.application.incidents.IncidentManagement;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences.HistoryVisibilityRule;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences.HistoryVisibilityRuleKind;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences.HistoryVisibilityState;
import br.com.contadoresassociados.folhas.contracts.clients.AuditEventModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewAuditEvent;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.text.Collator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/**
 * Histórico — linha do tempo filtrável de documentos, mensagens e cadastro, limpeza apenas da
 * visualização (a auditoria nunca é apagada) e registro/acompanhamento de ocorrências.
 * Porte do trecho de histórico do {@code MainViewModel}.
 */
public final class HistoryModel {

    public static final int DISPLAY_LIMIT = 500;
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm", OperationalPeriod.PT_BR);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final Collator COLLATOR = Collator.getInstance(OperationalPeriod.PT_BR);

    /** Opção com rótulo e ajuda. */
    public record Option<T>(T value, String label, String help) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record ClientOption(UUID clientId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record DocumentOption(UUID documentId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record HourOption(int hour, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Linha da linha do tempo ({@code HistoryTimelineRow}). */
    public record TimelineRow(UUID id, OffsetDateTime timestampUtc, String timestamp, String action, String clientName,
            String context, String result, boolean needsAttention) {
    }

    public static final List<Option<TimeScope>> TIME_SCOPES = List.of(
            new Option<>(TimeScope.ALL, "Todo o histórico", "Mostra todos os dias preservados, respeitando a competência escolhida no cabeçalho."),
            new Option<>(TimeScope.CALENDAR_DAY, "Um dia específico", "Mostra somente as ações registradas no dia escolhido."),
            new Option<>(TimeScope.CALENDAR_MONTH, "Um mês específico", "Mostra as ações de todo o mês e ano escolhidos."),
            new Option<>(TimeScope.CALENDAR_YEAR, "Um ano específico", "Mostra as ações de todo o ano escolhido."),
            new Option<>(TimeScope.CUSTOM_INTERVAL, "Intervalo com data e hora", "Mostra as ações entre o início e o fim informados."));

    public static final List<Option<HistoryVisibilityRuleKind>> CLEANUP_SCOPES = List.of(
            new Option<>(HistoryVisibilityRuleKind.ALL_UNTIL_NOW, "Tudo que aparece agora", "Oculta da tela todo o histórico atual; novos acontecimentos continuarão aparecendo."),
            new Option<>(HistoryVisibilityRuleKind.SELECTED_OPERATIONAL_PERIOD, "Competência selecionada", "Oculta somente ações ligadas ao mês e ano escolhidos no cabeçalho."),
            new Option<>(HistoryVisibilityRuleKind.CALENDAR_DAY, "Um dia", "Oculta as ações do dia escolhido."),
            new Option<>(HistoryVisibilityRuleKind.CLOCK_HOUR, "Uma hora", "Oculta as ações da hora escolhida."),
            new Option<>(HistoryVisibilityRuleKind.CUSTOM_INTERVAL, "Intervalo personalizado", "Oculta as ações entre as datas e horas informadas."));

    public static final List<Option<IncidentManagement.Status>> INCIDENT_STATUSES = List.of(
            new Option<>(IncidentManagement.Status.CONTAINED, "Contido", ""),
            new Option<>(IncidentManagement.Status.INVESTIGATING, "Em apuração", ""),
            new Option<>(IncidentManagement.Status.RESOLVED, "Resolvido", ""),
            new Option<>(IncidentManagement.Status.CLOSED, "Encerrado", ""));

    private final DocumentsModel documents;
    private final DispatchModel dispatch;
    private final ClientCatalogService catalog;
    private final IncidentManagement.Service incidentService;
    private final WorkspacePreferences.Store preferences;
    private final ShellState shell;
    private final UiTasks tasks;
    private final Clock clock;
    private final ZoneId zone;

    // ---------------------------------------------------------------- filtros de consulta
    public final ObservableList<Option<TimeScope>> timeScopes = FXCollections.observableArrayList(TIME_SCOPES);
    public final ObjectProperty<Option<TimeScope>> timeScope = new SimpleObjectProperty<>(TIME_SCOPES.getFirst());
    public final ObjectProperty<LocalDate> filterStartDate = new SimpleObjectProperty<>();
    public final StringProperty filterStartTime = new SimpleStringProperty("00:00");
    public final ObjectProperty<LocalDate> filterEndDate = new SimpleObjectProperty<>();
    public final StringProperty filterEndTime = new SimpleStringProperty("23:59");
    public final ObservableList<Integer> calendarYears = FXCollections.observableArrayList();
    public final ObjectProperty<Integer> calendarYear = new SimpleObjectProperty<>();
    public final ObservableList<OperationalPeriod.MonthOption> calendarMonths = FXCollections.observableArrayList();
    public final ObjectProperty<OperationalPeriod.MonthOption> calendarMonth = new SimpleObjectProperty<>();
    public final ObservableList<ClientOption> clientFilters = FXCollections.observableArrayList();
    public final ObjectProperty<ClientOption> clientFilter = new SimpleObjectProperty<>();
    public final ObservableList<DocumentOption> documentFilters = FXCollections.observableArrayList();
    public final ObjectProperty<DocumentOption> documentFilter = new SimpleObjectProperty<>();
    public final StringProperty searchText = new SimpleStringProperty("");
    public final StringProperty filterError = new SimpleStringProperty("");
    public final StringProperty appliedFilterSummary = new SimpleStringProperty("Exibindo todos os acontecimentos preservados.");

    // ---------------------------------------------------------------- limpeza da visualização
    public final ObservableList<Option<HistoryVisibilityRuleKind>> cleanupScopes = FXCollections.observableArrayList(CLEANUP_SCOPES);
    public final ObjectProperty<Option<HistoryVisibilityRuleKind>> cleanupScope = new SimpleObjectProperty<>(CLEANUP_SCOPES.getFirst());
    public final ObjectProperty<LocalDate> rangeStartDate = new SimpleObjectProperty<>();
    public final StringProperty rangeStartTime = new SimpleStringProperty("00:00");
    public final ObjectProperty<LocalDate> rangeEndDate = new SimpleObjectProperty<>();
    public final StringProperty rangeEndTime = new SimpleStringProperty("23:59");
    public final ObservableList<HourOption> hourOptions = FXCollections.observableArrayList();
    public final ObjectProperty<HourOption> cleanupHour = new SimpleObjectProperty<>();
    public final BooleanProperty showHidden = new SimpleBooleanProperty(false);

    // ---------------------------------------------------------------- linhas
    public final ObservableList<TimelineRow> reviewRows = FXCollections.observableArrayList();
    public final ObservableList<TimelineRow> dispatchRows = FXCollections.observableArrayList();
    public final ObservableList<TimelineRow> catalogRows = FXCollections.observableArrayList();
    public final BooleanProperty reviewExpanded = new SimpleBooleanProperty(true);
    public final BooleanProperty dispatchExpanded = new SimpleBooleanProperty(false);
    public final StringProperty truncationMessage = new SimpleStringProperty("");
    public final BooleanProperty truncated = new SimpleBooleanProperty(false);
    public final StringProperty hiddenCountText = new SimpleStringProperty("");
    public final BooleanProperty hasHidden = new SimpleBooleanProperty(false);

    // ---------------------------------------------------------------- ocorrências
    public final ObservableList<DeliveryAttempt> attempts;
    public final ObjectProperty<DeliveryAttempt> incidentAttempt = new SimpleObjectProperty<>();
    public final ObservableList<IncidentManagement.Category> incidentCategories = FXCollections.observableArrayList(
            IncidentManagement.Category.values());
    public final ObjectProperty<IncidentManagement.Category> incidentCategory = new SimpleObjectProperty<>(
            IncidentManagement.Category.POTENTIAL_WRONG_RECIPIENT);
    public final ObservableList<IncidentManagement.Severity> incidentSeverities = FXCollections.observableArrayList(
            IncidentManagement.Severity.values());
    public final ObjectProperty<IncidentManagement.Severity> incidentSeverity = new SimpleObjectProperty<>(
            IncidentManagement.Severity.LOW);
    public final StringProperty incidentSummary = new SimpleStringProperty("");
    public final ObservableList<IncidentManagement.Incident> incidents = FXCollections.observableArrayList();
    public final ObjectProperty<IncidentManagement.Incident> selectedIncident = new SimpleObjectProperty<>();
    public final ObservableList<Option<IncidentManagement.Status>> incidentStatuses = FXCollections.observableArrayList(INCIDENT_STATUSES);
    public final ObjectProperty<Option<IncidentManagement.Status>> incidentStatus = new SimpleObjectProperty<>(INCIDENT_STATUSES.getFirst());
    public final StringProperty incidentNote = new SimpleStringProperty("");
    public final BooleanProperty incidentsExpanded = new SimpleBooleanProperty(false);

    private Criteria activeFilter = Criteria.ALL;
    private UUID catalogClientId;
    private String catalogClientName;
    private final List<AuditEventModel> catalogAudit = new ArrayList<>();
    private HistoryVisibilityState visibility = HistoryVisibilityState.EMPTY;
    private final List<UUID> visibleIds = new ArrayList<>();
    private final Map<UUID, String> clientNames = new HashMap<>();

    public HistoryModel(DocumentsModel documents, DispatchModel dispatch, ClientCatalogService catalog,
            IncidentManagement.Service incidentService, WorkspacePreferences.Store preferences, ShellState shell,
            UiTasks tasks, Clock clock, ZoneId zone) {
        this.documents = Objects.requireNonNull(documents);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.catalog = catalog;
        this.incidentService = incidentService;
        this.preferences = preferences;
        this.shell = Objects.requireNonNull(shell);
        this.tasks = Objects.requireNonNull(tasks);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        this.attempts = dispatch.attempts;
        var now = clock.nowUtc().atZoneSameInstant(zone);
        var today = now.toLocalDate();
        filterStartDate.set(today);
        filterEndDate.set(today);
        filterEndTime.set(now.toLocalTime().format(HOUR_MINUTE));
        rangeStartDate.set(today);
        rangeEndDate.set(today);
        for (var h = 0; h < 24; h++) {
            hourOptions.add(new HourOption(h, "%02d:00–%02d:59".formatted(h, h)));
        }
        cleanupHour.set(hourOptions.getFirst());
        calendarMonths.setAll(shell.months().stream().filter(m -> m.month() != null && m.month() >= 1 && m.month() <= 12).toList());
        calendarMonth.set(calendarMonths.stream().filter(m -> m.month() == today.getMonthValue()).findFirst().orElse(null));
        calendarYear.set(today.getYear());
        refreshCalendarYears();
        clientFilters.add(new ClientOption(null, "Todos os clientes"));
        clientFilter.set(clientFilters.getFirst());
        documentFilters.add(new DocumentOption(null, "Todos os documentos"));
        documentFilter.set(documentFilters.getFirst());
        if (preferences != null) {
            try {
                visibility = preferences.load().map(WorkspacePreferences::historyVisibility).orElse(HistoryVisibilityState.EMPTY);
            } catch (RuntimeException e) {
                visibility = HistoryVisibilityState.EMPTY;
            }
        }
        ListChangeListener<Object> changed = c -> refresh();
        documents.auditEvents.addListener(changed);
        documents.documents.addListener(changed);
        dispatch.auditEvents.addListener(changed);
        dispatch.items.addListener(changed);
        shell.years().addListener((ListChangeListener<OperationalPeriod.YearOption>) c -> refreshCalendarYears());
        shell.selectedYear().addListener((obs, old, now2) -> refresh());
        shell.selectedMonth().addListener((obs, old, now2) -> refresh());
        showHidden.addListener((obs, old, now2) -> refresh());
        refresh();
    }

    private void refreshCalendarYears() {
        var keep = calendarYear.get();
        var years = new java.util.TreeSet<Integer>(Comparator.reverseOrder());
        shell.years().stream().map(OperationalPeriod.YearOption::year).filter(Objects::nonNull).forEach(years::add);
        if (keep != null) {
            years.add(keep);
        }
        calendarYears.setAll(years);
        calendarYear.set(keep);
    }

    // ================================================================ derivados

    private BooleanBinding scopeIs(TimeScope value) {
        return Bindings.createBooleanBinding(() -> timeScope.get() != null && timeScope.get().value() == value, timeScope);
    }

    public BooleanBinding isFilterDay() {
        return scopeIs(TimeScope.CALENDAR_DAY);
    }

    public BooleanBinding isFilterMonth() {
        return scopeIs(TimeScope.CALENDAR_MONTH);
    }

    public BooleanBinding isFilterYear() {
        return scopeIs(TimeScope.CALENDAR_YEAR);
    }

    public BooleanBinding isFilterCustom() {
        return scopeIs(TimeScope.CUSTOM_INTERVAL);
    }

    public StringBinding timeScopeHelp() {
        return Bindings.createStringBinding(() -> timeScope.get() == null ? "" : timeScope.get().help(), timeScope);
    }

    public BooleanBinding hasFilterError() {
        return Bindings.createBooleanBinding(() -> !filterError.get().isBlank(), filterError);
    }

    private BooleanBinding cleanupIs(HistoryVisibilityRuleKind kind) {
        return Bindings.createBooleanBinding(() -> cleanupScope.get() != null && cleanupScope.get().value() == kind, cleanupScope);
    }

    public BooleanBinding isDayCleanup() {
        return cleanupIs(HistoryVisibilityRuleKind.CALENDAR_DAY);
    }

    public BooleanBinding isHourCleanup() {
        return cleanupIs(HistoryVisibilityRuleKind.CLOCK_HOUR);
    }

    public BooleanBinding isCustomCleanup() {
        return cleanupIs(HistoryVisibilityRuleKind.CUSTOM_INTERVAL);
    }

    public StringBinding cleanupHelp() {
        return Bindings.createStringBinding(() -> cleanupScope.get() == null ? "" : cleanupScope.get().help(), cleanupScope);
    }

    public StringBinding cleanupActionLabel() {
        return Bindings.createStringBinding(() -> switch (cleanupScope.get().value()) {
            case CALENDAR_DAY -> "Ocultar este dia";
            case CLOCK_HOUR -> "Ocultar este bloco de uma hora";
            case CUSTOM_INTERVAL -> "Ocultar este intervalo";
            case SELECTED_OPERATIONAL_PERIOD -> "Ocultar esta competência";
            case ALL_UNTIL_NOW -> "Limpar a visualização atual";
        }, cleanupScope);
    }

    public StringBinding cleanupSelectionSummary() {
        Observable[] deps = {cleanupScope, rangeStartDate, rangeStartTime, rangeEndDate, rangeEndTime, cleanupHour,
                shell.selectedYear(), shell.selectedMonth()};
        return Bindings.createStringBinding(() -> {
            var start = rangeStartDate.get();
            return switch (cleanupScope.get().value()) {
                case ALL_UNTIL_NOW -> "Tudo que está visível agora será ocultado; novos registros continuarão aparecendo.";
                case SELECTED_OPERATIONAL_PERIOD -> "Competência escolhida: " + shell.workPeriodLabel().get() + ".";
                case CALENDAR_DAY -> start == null ? incomplete() : "Dia escolhido: " + start.format(DAY) + ".";
                case CLOCK_HOUR -> start == null ? incomplete()
                        : "Bloco escolhido: " + start.format(DAY) + ", " + cleanupHour.get().label() + ".";
                case CUSTOM_INTERVAL -> {
                    var st = parseTime(rangeStartTime.get());
                    var et = parseTime(rangeEndTime.get());
                    yield start == null || rangeEndDate.get() == null || st == null || et == null ? incomplete()
                            : "De " + start.format(DAY) + " às " + st.format(HOUR_MINUTE) + " até " + rangeEndDate.get().format(DAY)
                                    + " às " + et.format(HOUR_MINUTE) + ".";
                }
            };
        }, deps);
    }

    private static String incomplete() {
        return "Complete o período que deseja retirar apenas desta visualização.";
    }

    public StringBinding reviewHeader() {
        return Bindings.createStringBinding(() -> "Documentos conferidos e aprovados · " + reviewRows.size(), reviewRows);
    }

    public StringBinding dispatchHeader() {
        return Bindings.createStringBinding(() -> "Mensagens preparadas e concluídas · " + dispatchRows.size(), dispatchRows);
    }

    public StringBinding catalogHeader() {
        return Bindings.createStringBinding(() -> catalogClientId == null ? "Alterações cadastrais"
                : "Alterações do cadastro · " + catalogClientName, catalogRows);
    }

    public StringBinding catalogEmptyMessage() {
        return Bindings.createStringBinding(() -> catalogClientId == null
                ? "Escolha um cliente no filtro acima e aplique para consultar as alterações desse cadastro."
                : "Ainda não há alterações no período escolhido para " + catalogClientName + ".", catalogRows);
    }

    public BooleanBinding hasCatalogRows() {
        return Bindings.isNotEmpty(catalogRows);
    }

    public BooleanBinding hasIncidentAttempt() {
        return incidentAttempt.isNotNull();
    }

    public static String attemptLabel(DeliveryAttempt attempt, ZoneId zone) {
        return attempt == null ? "" : "Tentativa em " + attempt.startedAtUtc().atZoneSameInstant(zone).format(TIMESTAMP);
    }

    public ZoneId zone() {
        return zone;
    }

    // ================================================================ projeção e filtros

    private HistoryFiltering.ProjectionIndex index() {
        return HistoryFiltering.ProjectionIndex.create(documents.documents, documents.groups, dispatch.items, clientNames);
    }

    private boolean hasNoPeriodFilter() {
        var y = shell.selectedYear().get();
        var m = shell.selectedMonth().get();
        return (y == null || y.year() == null) && (m == null || m.month() == null);
    }

    private boolean reviewMatchesPeriod(ReviewAuditEvent a) {
        if (hasNoPeriodFilter()) {
            return true;
        }
        if (a.documentId() != null && documents.documents.stream()
                .anyMatch(d -> d.id().equals(a.documentId()) && documents.matchesSelectedPeriod(d.period()))) {
            return true;
        }
        if (a.groupId() != null && documents.groups.stream()
                .anyMatch(g -> g.id().equals(a.groupId()) && documents.matchesSelectedPeriod(g))) {
            return true;
        }
        var periods = auditValue(a, "periods");
        if (periods == null) {
            periods = auditValue(a, "period");
        }
        return periods != null && java.util.Arrays.stream(periods.split("\\|")).map(String::strip).filter(s -> !s.isEmpty())
                .anyMatch(dispatch::matchesSelectedPeriod);
    }

    private boolean dispatchMatchesPeriod(DispatchAuditEvent a) {
        if (hasNoPeriodFilter()) {
            return true;
        }
        return a.dispatchItemId() != null && dispatch.items.stream()
                .anyMatch(i -> i.id().equals(a.dispatchItemId()) && dispatch.matchesSelectedPeriod(i.periodLabel()))
                || a.groupId() != null && documents.groups.stream()
                        .anyMatch(g -> g.id().equals(a.groupId()) && documents.matchesSelectedPeriod(g));
    }

    private boolean isVisible(UUID id, OffsetDateTime timestamp, boolean ignoreShowHidden) {
        if (showHidden.get() && !ignoreShowHidden) {
            return true;
        }
        return visibility.rules().stream().noneMatch(rule -> rule.eventIds().contains(id)
                || (rule.startUtc() != null || rule.endExclusiveUtc() != null)
                        && (rule.startUtc() == null || !timestamp.isBefore(rule.startUtc()))
                        && (rule.endExclusiveUtc() == null || timestamp.isBefore(rule.endExclusiveUtc())));
    }

    private List<ReviewAuditEvent> filteredReview() {
        var idx = index();
        return HistoryFiltering.apply(documents.auditEvents.stream().filter(this::reviewMatchesPeriod).toList(),
                a -> withFriendlyText(idx.project(a), a.action(), a.reason()),
                activeFilter).stream().filter(a -> isVisible(a.id(), a.timestampUtc(), false))
                .sorted(Comparator.comparing(ReviewAuditEvent::timestampUtc).thenComparing(ReviewAuditEvent::id).reversed())
                .toList();
    }

    private List<DispatchAuditEvent> filteredDispatch() {
        var idx = index();
        return HistoryFiltering.apply(dispatch.auditEvents.stream().filter(this::dispatchMatchesPeriod).toList(),
                a -> withFriendlyText(idx.project(a), a.action(), a.outcome()),
                activeFilter).stream().filter(a -> isVisible(a.id(), a.timestampUtc(), false))
                .sorted(Comparator.comparing(DispatchAuditEvent::timestampUtc).thenComparing(DispatchAuditEvent::id).reversed())
                .toList();
    }

    private List<AuditEventModel> filteredCatalog() {
        if (catalogClientId == null) {
            return List.of();
        }
        var idx = index();
        var criteria = new Criteria(activeFilter.timeWindow(), null, null, catalogClientId, null, null, activeFilter.searchText());
        return HistoryFiltering.apply(catalogAudit, a -> idx.project(a, catalogClientId, catalogClientName), criteria).stream()
                .filter(a -> isVisible(a.id(), a.timestampUtc(), false))
                .sorted(Comparator.comparing(AuditEventModel::timestampUtc).thenComparing(AuditEventModel::id).reversed())
                .toList();
    }

    /** Melhoria: a busca também encontra o texto exibido (“liberado”, “aprovada”), não só o código técnico. */
    private static HistoryFiltering.Entry withFriendlyText(HistoryFiltering.Entry e, String action, String outcome) {
        return new HistoryFiltering.Entry(e.eventId(), e.timestampUtc(), e.clientId(), e.clientName(), e.competences(),
                e.documents(), String.join(" ", e.searchText() == null ? "" : e.searchText(), FriendlyText.action(action),
                        FriendlyText.action(outcome)));
    }

    /** Recalcula as três seções, o aviso de limite e a contagem de ocultos. */
    public void refresh() {
        var review = filteredReview();
        var disp = filteredDispatch();
        var cat = filteredCatalog();
        reviewRows.setAll(review.stream().limit(DISPLAY_LIMIT).map(this::reviewRow).toList());
        dispatchRows.setAll(disp.stream().limit(DISPLAY_LIMIT).map(this::dispatchRow).toList());
        catalogRows.setAll(cat.stream().limit(DISPLAY_LIMIT).map(this::catalogRow).toList());
        visibleIds.clear();
        Stream.of(reviewRows, dispatchRows, catalogRows).flatMap(List::stream).map(TimelineRow::id).forEach(visibleIds::add);
        var over = Math.max(0, review.size() - DISPLAY_LIMIT) + Math.max(0, disp.size() - DISPLAY_LIMIT)
                + Math.max(0, cat.size() - DISPLAY_LIMIT);
        truncated.set(over > 0);
        truncationMessage.set("A tela mostra os " + DISPLAY_LIMIT + " registros mais recentes de cada seção. " + over
                + " registro(s) adicional(is) continuam preservados na auditoria.");
        var all = new LinkedHashMap<UUID, OffsetDateTime>();
        documents.auditEvents.forEach(a -> all.putIfAbsent(a.id(), a.timestampUtc()));
        dispatch.auditEvents.forEach(a -> all.putIfAbsent(a.id(), a.timestampUtc()));
        catalogAudit.forEach(a -> all.putIfAbsent(a.id(), a.timestampUtc()));
        var hidden = all.entrySet().stream().filter(e -> !isVisible(e.getKey(), e.getValue(), true)).count();
        hasHidden.set(hidden > 0);
        hiddenCountText.set("Ocultos nesta visualização: " + hidden + " registro(s).");
    }

    private String timestamp(OffsetDateTime utc) {
        return utc.atZoneSameInstant(zone).format(TIMESTAMP);
    }

    private TimelineRow reviewRow(ReviewAuditEvent audit) {
        var document = audit.documentId() == null ? null
                : documents.documents.stream().filter(d -> d.id().equals(audit.documentId())).findFirst().orElse(null);
        var auditedClient = parseHexUuid(auditValue(audit, "client"));
        var group = audit.groupId() != null ? groupById(audit.groupId())
                : document != null && document.groupId() != null ? groupById(document.groupId())
                : auditedClient != null ? documents.groups.stream().filter(g -> auditedClient.equals(g.clientId())).findFirst()
                        .orElse(null) : null;
        var clientName = firstNonNull(document == null ? null : document.clientDisplayName(),
                group == null ? null : group.clientDisplayName(), auditValue(audit, "client-name"),
                auditedClient == null ? null : clientNames.get(auditedClient), "Cliente não disponível");
        var periodsText = auditValue(audit, "periods");
        var periods = periodsText == null ? null : java.util.Arrays.stream(periodsText.split("\\|")).map(String::strip)
                .filter(s -> !s.isEmpty()).map(HistoryModel::formatAuditPeriod).toList();
        var single = auditValue(audit, "period");
        var period = firstNonNull(document == null || document.period() == null ? null : document.period().displayLabel(),
                periods != null && !periods.isEmpty() ? String.join(", ", periods) : null,
                single == null ? null : formatAuditPeriod(single), group == null ? null : group.periodLabel(),
                "Competência não disponível");
        var docCount = auditValue(audit, "documents");
        var groupCount = auditValue(audit, "groups");
        var type = parseType(auditValue(audit, "type"));
        var documentContext = document != null ? DocumentPresentation.label(document.documentType())
                : docCount != null ? docCount + " documento(s) em " + (groupCount == null ? "1" : groupCount) + " conjunto(s)"
                : type != null ? DocumentPresentation.label(type)
                : group != null ? group.documentIds().size() + " documento(s): " + documents.describeTypes(group)
                : "Detalhes documentais não disponíveis nesta versão";
        var result = audit.reason() == null || audit.reason().isBlank() ? "Ação registrada com auditoria preservada."
                : FriendlyText.action(audit.reason());
        var action = FriendlyText.action(audit.action());
        var lower = action.toLowerCase(OperationalPeriod.PT_BR);
        return new TimelineRow(audit.id(), audit.timestampUtc(), timestamp(audit.timestampUtc()), action, clientName,
                period + " • " + documentContext, result, lower.contains("revogada") || lower.contains("retirado"));
    }

    private TimelineRow dispatchRow(DispatchAuditEvent audit) {
        var item = audit.dispatchItemId() == null ? null
                : dispatch.items.stream().filter(i -> i.id().equals(audit.dispatchItemId())).findFirst().orElse(null);
        var group = audit.groupId() != null ? groupById(audit.groupId()) : item != null ? groupById(item.groupId()) : null;
        var clientName = firstNonNull(item == null ? null : item.clientDisplayName(),
                group == null ? null : group.clientDisplayName(), "Cliente não disponível");
        var period = firstNonNull(item == null ? null : item.periodLabel(), group == null ? null : group.periodLabel(),
                "Competência não disponível");
        String attachments;
        if (item != null && item.message() != null && !item.message().attachments().isEmpty()) {
            var list = item.message().attachments();
            attachments = list.size() + " anexo(s): " + list.stream().map(a -> DocumentPresentation.label(a.documentType()))
                    .distinct().collect(Collectors.joining(", "));
        } else if (group != null) {
            attachments = group.documentIds().size() + " documento(s): " + documents.describeTypes(group);
        } else {
            attachments = "Detalhes documentais não disponíveis nesta versão";
        }
        var outcome = audit.outcome() == null ? "" : audit.outcome();
        var lower = outcome.toLowerCase(java.util.Locale.ROOT);
        var attention = lower.contains("failed") || lower.contains("failure") || lower.contains("error")
                || lower.contains("ambiguous") || audit.errorCode() != null && !audit.errorCode().isBlank();
        return new TimelineRow(audit.id(), audit.timestampUtc(), timestamp(audit.timestampUtc()),
                FriendlyText.action(audit.action()), clientName, period + " • " + attachments, FriendlyText.action(outcome),
                attention);
    }

    private TimelineRow catalogRow(AuditEventModel audit) {
        return new TimelineRow(audit.id(), audit.timestampUtc(), timestamp(audit.timestampUtc()),
                FriendlyText.action(audit.action()), catalogClientName, "Alteração registrada com dados protegidos", "", false);
    }

    private br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup groupById(UUID id) {
        return documents.groups.stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    static String auditValue(ReviewAuditEvent audit, String key) {
        var v = auditValue(audit.newValue(), key);
        return v != null ? v : auditValue(audit.previousValue(), key);
    }

    private static String auditValue(String value, String key) {
        if (value == null) {
            return null;
        }
        for (var part : value.split(";")) {
            var kv = part.strip().split(":", 2);
            if (kv.length == 2 && kv[0].strip().equalsIgnoreCase(key)) {
                return kv[1].strip();
            }
        }
        return null;
    }

    static String formatAuditPeriod(String value) {
        var ym = ReportsModel.yearMonth(value);
        return ym == null ? value : "%02d/%04d".formatted(ym[1], ym[0]);
    }

    private static UUID parseHexUuid(String value) {
        if (value == null || value.length() != 32 || !value.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return null;
        }
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16) + "-"
                + value.substring(16, 20) + "-" + value.substring(20));
    }

    private static RecognizedDocumentType parseType(String text) {
        if (text == null) {
            return null;
        }
        var normalized = text.strip().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
        for (var t : RecognizedDocumentType.values()) {
            if (t.name().equals(normalized)) {
                return t;
            }
        }
        return null;
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

    private static LocalTime parseTime(String text) {
        if (text == null) {
            return null;
        }
        var t = text.strip();
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        var parts = t.split(":");
        var h = Integer.parseInt(parts[0]);
        var m = Integer.parseInt(parts[1]);
        return h > 23 || m > 59 ? null : LocalTime.of(h, m);
    }

    // ================================================================ opções de cliente e documento

    /** Ao abrir a seção: atualiza clientes, documentos e ocorrências. */
    public void open() {
        loadFilterOptions();
        loadIncidents();
        refresh();
    }

    private void loadFilterOptions() {
        var names = new LinkedHashMap<UUID, String>();
        documents.documents.stream().filter(d -> d.clientId() != null)
                .forEach(d -> names.putIfAbsent(d.clientId(), historical(d.clientDisplayName())));
        dispatch.items.forEach(i -> names.putIfAbsent(i.clientId(), historical(i.clientDisplayName())));
        applyClientOptions(names);
        if (catalog != null) {
            tasks.run(() -> catalog.search(null, null, null, 0, 500).items(), items -> {
                items.forEach(i -> names.put(i.id(), i.displayName()));
                applyClientOptions(names);
            }, error -> shell.status(ErrorMessages.friendly(error)));
        }
        var selectedDoc = documentFilter.get() == null ? null : documentFilter.get().documentId();
        var options = new LinkedHashMap<UUID, DocumentOption>();
        documents.documents.forEach(d -> options.putIfAbsent(d.id(), new DocumentOption(d.id(),
                DocumentPresentation.label(d.documentType()) + " · " + (d.period() == null ? "" : d.period().displayLabel())
                        + " · " + historical(d.clientDisplayName()) + " · " + d.fileName())));
        dispatch.items.stream().filter(i -> i.message() != null).forEach(i -> i.message().attachments()
                .forEach(a -> options.putIfAbsent(a.documentId(), new DocumentOption(a.documentId(),
                        DocumentPresentation.label(a.documentType()) + " · " + i.periodLabel() + " · "
                                + historical(i.clientDisplayName()) + " · " + a.fileName()))));
        var list = new ArrayList<DocumentOption>();
        list.add(new DocumentOption(null, "Todos os documentos"));
        options.values().stream().sorted(Comparator.comparing(DocumentOption::label, COLLATOR)).forEach(list::add);
        documentFilters.setAll(list);
        documentFilter.set(list.stream().filter(o -> Objects.equals(o.documentId(), selectedDoc)).findFirst().orElse(list.getFirst()));
    }

    private static String historical(String name) {
        return name == null || name.isBlank() ? "Cliente sem nome registrado" : name;
    }

    private void applyClientOptions(Map<UUID, String> names) {
        clientNames.clear();
        clientNames.putAll(names);
        var selected = clientFilter.get() == null ? null : clientFilter.get().clientId();
        var list = new ArrayList<ClientOption>();
        list.add(new ClientOption(null, "Todos os clientes"));
        names.entrySet().stream().sorted(Map.Entry.comparingByValue(COLLATOR))
                .forEach(e -> list.add(new ClientOption(e.getKey(), e.getValue())));
        clientFilters.setAll(list);
        clientFilter.set(list.stream().filter(o -> Objects.equals(o.clientId(), selected)).findFirst().orElse(list.getFirst()));
    }

    // ================================================================ comandos de filtro

    TimeWindow buildTimeWindow() {
        var scope = timeScope.get().value();
        return switch (scope) {
            case ALL -> TimeWindow.ALL;
            case CALENDAR_DAY -> {
                if (filterStartDate.get() == null) {
                    throw new IllegalArgumentException("Preencha todos os campos de data e hora do filtro.");
                }
                yield TimeWindow.forDay(filterStartDate.get(), zone);
            }
            case CALENDAR_MONTH -> {
                if (calendarYear.get() == null || calendarMonth.get() == null || calendarMonth.get().month() == null) {
                    throw new IllegalArgumentException("Preencha todos os campos de data e hora do filtro.");
                }
                yield TimeWindow.forMonth(calendarYear.get(), calendarMonth.get().month(), zone);
            }
            case CALENDAR_YEAR -> {
                if (calendarYear.get() == null) {
                    throw new IllegalArgumentException("Preencha todos os campos de data e hora do filtro.");
                }
                yield TimeWindow.forYear(calendarYear.get(), zone);
            }
            case CUSTOM_INTERVAL -> {
                var st = parseTime(filterStartTime.get());
                var et = parseTime(filterEndTime.get());
                if (filterStartDate.get() == null || filterEndDate.get() == null || st == null || et == null) {
                    throw new IllegalArgumentException("Preencha todos os campos de data e hora do filtro (horário no formato HH:mm).");
                }
                yield TimeWindow.forLocalInterval(filterStartDate.get(), st, filterEndDate.get(), et, zone);
            }
        };
    }

    public void applyFilters() {
        filterError.set("");
        TimeWindow window;
        try {
            window = buildTimeWindow();
        } catch (IllegalArgumentException e) {
            filterError.set(e.getMessage());
            shell.status(e.getMessage());
            return;
        }
        var client = clientFilter.get() == null ? null : clientFilter.get();
        var doc = documentFilter.get() == null ? null : documentFilter.get().documentId();
        var text = searchText.get() == null || searchText.get().isBlank() ? null : searchText.get().strip();
        var candidate = new Criteria(window, null, null, client == null ? null : client.clientId(), doc, null, text);
        if (client != null && client.clientId() != null && catalog != null) {
            tasks.run(() -> catalog.audit(client.clientId()), audit -> {
                catalogAudit.clear();
                catalogAudit.addAll(audit);
                catalogClientId = client.clientId();
                catalogClientName = client.label();
                finishApply(candidate);
            }, error -> {
                filterError.set("Os filtros não foram aplicados. " + ErrorMessages.friendly(error));
                shell.status(filterError.get());
            });
            return;
        }
        catalogAudit.clear();
        catalogClientId = null;
        catalogClientName = null;
        finishApply(candidate);
    }

    private void finishApply(Criteria candidate) {
        activeFilter = candidate;
        appliedFilterSummary.set(buildAppliedSummary());
        refresh();
        shell.status(appliedFilterSummary.get());
    }

    String buildAppliedSummary() {
        var time = switch (timeScope.get().value()) {
            case ALL -> "todos os dias";
            case CALENDAR_DAY -> filterStartDate.get() == null ? "período escolhido" : filterStartDate.get().format(DAY);
            case CALENDAR_MONTH -> calendarMonth.get() == null ? "período escolhido"
                    : calendarMonth.get().label() + " " + calendarYear.get();
            case CALENDAR_YEAR -> "ano de " + calendarYear.get();
            case CUSTOM_INTERVAL -> {
                var st = parseTime(filterStartTime.get());
                var et = parseTime(filterEndTime.get());
                yield st == null || et == null ? "período escolhido" : filterStartDate.get().format(DAY) + " "
                        + st.format(HOUR_MINUTE) + " a " + filterEndDate.get().format(DAY) + " " + et.format(HOUR_MINUTE);
            }
        };
        var client = clientFilter.get() == null || clientFilter.get().clientId() == null ? "todos os clientes"
                : clientFilter.get().label();
        var document = documentFilter.get() == null || documentFilter.get().documentId() == null ? "todos os documentos"
                : documentFilter.get().label();
        var search = searchText.get() == null || searchText.get().isBlank() ? "" : " · busca “" + searchText.get().strip() + "”";
        return "Exibindo " + time + " · " + client + " · " + document + " · competência " + shell.workPeriodLabel().get() + search
                + ".";
    }

    public void clearFilters() {
        var now = clock.nowUtc().atZoneSameInstant(zone);
        timeScope.set(TIME_SCOPES.getFirst());
        filterStartDate.set(now.toLocalDate());
        filterStartTime.set("00:00");
        filterEndDate.set(now.toLocalDate());
        filterEndTime.set(now.toLocalTime().format(HOUR_MINUTE));
        calendarYear.set(now.getYear());
        calendarMonth.set(calendarMonths.stream().filter(m -> m.month() == now.getMonthValue()).findFirst().orElse(null));
        clientFilter.set(clientFilters.isEmpty() ? null : clientFilters.getFirst());
        documentFilter.set(documentFilters.isEmpty() ? null : documentFilters.getFirst());
        searchText.set("");
        filterError.set("");
        activeFilter = Criteria.ALL;
        catalogClientId = null;
        catalogClientName = null;
        catalogAudit.clear();
        appliedFilterSummary.set("Exibindo todos os acontecimentos preservados.");
        refresh();
        shell.status("Filtros próprios do Histórico removidos. A competência do cabeçalho foi mantida.");
    }

    // ================================================================ limpar / restaurar a visualização

    public void archiveView() {
        var kind = cleanupScope.get().value();
        var now = clock.nowUtc();
        OffsetDateTime start = null;
        OffsetDateTime end = null;
        List<UUID> ids = List.of();
        try {
            switch (kind) {
                case ALL_UNTIL_NOW -> {
                    ids = List.copyOf(new LinkedHashSet<>(visibleIds));
                    if (ids.isEmpty()) {
                        shell.status("Não há acontecimentos visíveis nos filtros atuais para ocultar.");
                        return;
                    }
                }
                case SELECTED_OPERATIONAL_PERIOD -> {
                    if (!documents.singleMonthSelected()) {
                        shell.status("Escolha um único mês e ano no cabeçalho para limpar essa competência da visualização.");
                        return;
                    }
                    var set = new LinkedHashSet<UUID>();
                    documents.auditEvents.stream().filter(this::reviewMatchesPeriod).forEach(a -> set.add(a.id()));
                    dispatch.auditEvents.stream().filter(this::dispatchMatchesPeriod).forEach(a -> set.add(a.id()));
                    ids = List.copyOf(set);
                    if (ids.isEmpty()) {
                        shell.status("Não há ações dessa competência para ocultar da tela.");
                        return;
                    }
                }
                case CALENDAR_DAY -> {
                    if (rangeStartDate.get() == null) {
                        shell.status("Escolha o dia que deseja limpar da visualização.");
                        return;
                    }
                    var window = TimeWindow.forDay(rangeStartDate.get(), zone);
                    start = window.startUtc();
                    end = window.endExclusiveUtc();
                }
                case CLOCK_HOUR -> {
                    if (rangeStartDate.get() == null) {
                        shell.status("Escolha o dia e a hora que deseja limpar da visualização.");
                        return;
                    }
                    var local = rangeStartDate.get().atTime(cleanupHour.get().hour(), 0);
                    var window = TimeWindow.forLocalInterval(local.toLocalDate(), local.toLocalTime(),
                            local.plusHours(1).toLocalDate(), local.plusHours(1).toLocalTime(), zone);
                    start = window.startUtc();
                    end = window.endExclusiveUtc();
                }
                case CUSTOM_INTERVAL -> {
                    var st = parseTime(rangeStartTime.get());
                    var et = parseTime(rangeEndTime.get());
                    if (rangeStartDate.get() == null || rangeEndDate.get() == null || st == null || et == null) {
                        shell.status("Informe o início e o fim do intervalo que deseja ocultar.");
                        return;
                    }
                    var window = TimeWindow.forLocalInterval(rangeStartDate.get(), st, rangeEndDate.get(), et, zone);
                    start = window.startUtc();
                    end = window.endExclusiveUtc();
                }
            }
        } catch (IllegalArgumentException e) {
            shell.status(e.getMessage().startsWith("O fim do intervalo") ? "O fim do intervalo deve ser posterior ao início."
                    : e.getMessage());
            return;
        }
        var y = shell.selectedYear().get() == null ? null : shell.selectedYear().get().year();
        var m = shell.selectedMonth().get() == null ? null : shell.selectedMonth().get().month();
        var rules = new ArrayList<>(visibility.rules());
        rules.add(new HistoryVisibilityRule(UUID.randomUUID(), kind, now, start, end, y, m, ids));
        visibility = new HistoryVisibilityState(rules);
        showHidden.set(false);
        persistVisibility();
        refresh();
        shell.status("Histórico limpo da visualização. Os registros de auditoria foram preservados e podem ser restaurados na tela.");
    }

    public void restoreView() {
        visibility = HistoryVisibilityState.EMPTY;
        showHidden.set(false);
        persistVisibility();
        refresh();
        shell.status("Todo o histórico preservado voltou a aparecer na tela.");
    }

    private void persistVisibility() {
        if (preferences == null) {
            return;
        }
        var state = visibility;
        tasks.run(() -> {
            var current = preferences.load().orElseGet(() -> WorkspacePreferences.defaults(
                    clock.nowUtc().atZoneSameInstant(Clock.BRAZIL).toOffsetDateTime()));
            preferences.save(current.withHistoryVisibility(state));
        }, () -> { }, error -> shell.status("A preferência de visualização não pôde ser salva: " + ErrorMessages.friendly(error)));
    }

    // ================================================================ ocorrências

    /** Vindo de Mensagens (“Relatar ocorrência”): abre o painel já com a tentativa escolhida. */
    public void startIncidentFor(DeliveryAttempt attempt) {
        incidentAttempt.set(attempt);
        incidentsExpanded.set(true);
    }

    private void loadIncidents() {
        if (incidentService == null) {
            return;
        }
        tasks.run(incidentService::load, this::applyIncidents, error -> shell.status(ErrorMessages.friendly(error)));
    }

    private void applyIncidents(IncidentManagement.Workspace workspace) {
        var selectedId = selectedIncident.get() == null ? null : selectedIncident.get().id();
        incidents.setAll(workspace.incidents().stream()
                .sorted(Comparator.comparing(IncidentManagement.Incident::updatedAtUtc).reversed()).limit(DISPLAY_LIMIT).toList());
        selectedIncident.set(incidents.stream().filter(i -> i.id().equals(selectedId)).findFirst()
                .orElse(incidents.isEmpty() ? null : incidents.getFirst()));
    }

    public void openIncident() {
        var attempt = incidentAttempt.get();
        if (incidentService == null || attempt == null) {
            shell.status("Selecione uma tentativa de entrega antes de abrir a ocorrência.");
            return;
        }
        var request = new IncidentManagement.OpenRequest(attempt.id(), incidentCategory.get(), incidentSeverity.get(),
                incidentSummary.get());
        tasks.run(() -> incidentService.open(request), ws -> {
            applyIncidents(ws);
            incidentSummary.set("");
            shell.status("Ocorrência registrada com histórico protegido. Agora contenha e apure antes de repetir qualquer envio.");
        }, this::fail);
    }

    public void transitionIncident() {
        var incident = selectedIncident.get();
        if (incidentService == null || incident == null) {
            shell.status("Selecione uma ocorrência ativa para atualizar.");
            return;
        }
        var request = new IncidentManagement.TransitionRequest(incident.id(), incident.version(), incidentStatus.get().value(),
                incidentNote.get());
        tasks.run(() -> incidentService.transition(request), ws -> {
            applyIncidents(ws);
            incidentNote.set("");
            shell.status("Situação da ocorrência atualizada; o evento anterior foi preservado.");
        }, this::fail);
    }

    private void fail(Throwable error) {
        shell.status(switch (error) {
            case IncidentManagement.IncidentException e -> "Não foi possível concluir esta etapa: " + e.getMessage();
            case br.com.contadoresassociados.folhas.application.identity.PermissionDeniedException e ->
                    "Seu perfil não tem permissão para esta ação.";
            default -> ErrorMessages.friendly(error);
        });
    }
}
