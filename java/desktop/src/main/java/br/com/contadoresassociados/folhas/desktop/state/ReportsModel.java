package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresenter;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchReportFilter;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowException;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowService;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchReportResult;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.nio.file.Path;
import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/**
 * Relatórios — recorte por cliente e período independente da competência de trabalho,
 * situação atual das comunicações e geração de planilha, PDF e CSVs (porte do
 * {@code MainViewModel}, trecho de relatórios).
 */
public final class ReportsModel {

    /** Escopo de período ({@code ReportScopeOption}). */
    public enum Scope {
        MONTH("Um mês", "Escolha uma competência mensal sem alterar o período de trabalho do restante do aplicativo."),
        YEAR("Um ano", "Inclui todos os meses do ano escolhido."),
        RANGE("Intervalo de meses", "Inclui as competências entre o mês inicial e o final, inclusive."),
        ALL_PERIODS("Todos os períodos", "Consolida todo o acervo disponível nesta instalação.");

        private final String label;
        private final String help;

        Scope(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String help() {
            return help;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Filtro de clientes ({@code ReportClientFilterOption}). */
    public enum ClientFilter {
        ALL_CLIENTS("Todos os clientes", "Inclui todos os clientes encontrados no período escolhido."),
        SELECTED_CLIENT("Um cliente específico", "Inclui somente o cliente escolhido, sem perder o filtro de período.");

        private final String label;
        private final String help;

        ClientFilter(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String help() {
            return help;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Cliente do seletor (cadastro ou histórico). */
    public record ReportClient(UUID id, String displayName, boolean active) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Linha de situação da comunicação ({@code CommunicationStatusRow}). */
    public record CommunicationRow(UUID itemId, String clientName, String period, String messageReference,
            String groupSummary, String documentSummary, String operationResult, String deliveryStatus, String nextAction,
            boolean needsAttention) {
    }

    private static final Collator COLLATOR = Collator.getInstance(OperationalPeriod.PT_BR);

    private final DispatchWorkflowService service;
    private final ClientCatalogService catalog;
    private final DispatchModel dispatch;
    private final DocumentsModel documents;
    private final ShellState shell;
    private final UiTasks tasks;

    public final ObservableList<Scope> scopes = FXCollections.observableArrayList(Scope.values());
    public final ObservableList<ClientFilter> clientFilters = FXCollections.observableArrayList(ClientFilter.values());
    public final ObservableList<ReportClient> clients = FXCollections.observableArrayList();
    public final ObservableList<OperationalPeriod.YearOption> yearOptions = FXCollections.observableArrayList();
    public final ObservableList<OperationalPeriod.MonthOption> monthOptions = FXCollections.observableArrayList();
    public final ObservableList<CommunicationRow> rows = FXCollections.observableArrayList();

    public final ObjectProperty<Scope> scope = new SimpleObjectProperty<>(Scope.MONTH);
    public final ObjectProperty<ClientFilter> clientFilter = new SimpleObjectProperty<>(ClientFilter.ALL_CLIENTS);
    public final ObjectProperty<ReportClient> client = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.YearOption> year = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.MonthOption> month = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.YearOption> startYear = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.MonthOption> startMonth = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.YearOption> endYear = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.MonthOption> endMonth = new SimpleObjectProperty<>();

    public final StringProperty outputDirectory = new SimpleStringProperty();
    public final StringProperty lastReportPath = new SimpleStringProperty("Nenhum relatório gerado nesta sessão.");
    public final StringProperty lastReportPdfPath = new SimpleStringProperty("Nenhum PDF gerado nesta sessão.");

    public ReportsModel(DispatchWorkflowService service, ClientCatalogService catalog, DispatchModel dispatch,
            DocumentsModel documents, ShellState shell, UiTasks tasks, Path defaultOutputDirectory, int currentYear,
            int currentMonth) {
        this.service = Objects.requireNonNull(service);
        this.catalog = catalog;
        this.dispatch = Objects.requireNonNull(dispatch);
        this.documents = Objects.requireNonNull(documents);
        this.shell = Objects.requireNonNull(shell);
        this.tasks = Objects.requireNonNull(tasks);
        outputDirectory.set(defaultOutputDirectory.toString());
        monthOptions.setAll(shell.months().stream().filter(m -> m.month() != null && m.month() >= 1 && m.month() <= 12).toList());
        refreshYears();
        shell.years().addListener((ListChangeListener<OperationalPeriod.YearOption>) c -> refreshYears());
        // Padrões: competência atual ({@code InitializePeriodOptions}).
        year.set(yearFor(currentYear));
        startYear.set(yearFor(currentYear));
        endYear.set(yearFor(currentYear));
        month.set(monthFor(currentMonth));
        startMonth.set(monthFor(currentMonth));
        endMonth.set(monthFor(currentMonth));
        Observable[] triggers = {scope, clientFilter, client, year, month, startYear, startMonth, endYear, endMonth,
                dispatch.items, dispatch.attempts};
        for (var t : triggers) {
            t.addListener(o -> refreshRows());
        }
        refreshRows();
    }

    private void refreshYears() {
        var keep = new Integer[] {id(year.get()), id(startYear.get()), id(endYear.get())};
        yearOptions.setAll(shell.years().stream().filter(y -> y.year() != null).toList());
        // Reassocia as escolhas às novas instâncias ({@code reboundReportYear}).
        year.set(yearFor(keep[0]));
        startYear.set(yearFor(keep[1]));
        endYear.set(yearFor(keep[2]));
    }

    private static Integer id(OperationalPeriod.YearOption option) {
        return option == null ? null : option.year();
    }

    private OperationalPeriod.YearOption yearFor(Integer value) {
        return value == null ? null : yearOptions.stream().filter(y -> value.equals(y.year())).findFirst().orElse(null);
    }

    private OperationalPeriod.MonthOption monthFor(int value) {
        return monthOptions.stream().filter(m -> m.month() == value).findFirst().orElse(null);
    }

    // ================================================================ derivados

    private Observable[] deps() {
        return new Observable[] {scope, clientFilter, client, year, month, startYear, startMonth, endYear, endMonth, rows};
    }

    public BooleanBinding isClientScope() {
        return Bindings.createBooleanBinding(() -> clientFilter.get() == ClientFilter.SELECTED_CLIENT, clientFilter);
    }

    public BooleanBinding isMonthScope() {
        return Bindings.createBooleanBinding(() -> scope.get() == Scope.MONTH, scope);
    }

    public BooleanBinding isYearScope() {
        return Bindings.createBooleanBinding(() -> scope.get() == Scope.YEAR, scope);
    }

    public BooleanBinding isRangeScope() {
        return Bindings.createBooleanBinding(() -> scope.get() == Scope.RANGE, scope);
    }

    public StringBinding scopeHelp() {
        return Bindings.createStringBinding(() -> scope.get() == null ? "" : scope.get().help(), scope);
    }

    public StringBinding clientFilterHelp() {
        return Bindings.createStringBinding(() -> clientFilter.get() == null ? "" : clientFilter.get().help(), clientFilter);
    }

    public BooleanBinding hasRows() {
        return Bindings.isNotEmpty(rows);
    }

    public BooleanBinding canExport() {
        return Bindings.createBooleanBinding(this::canExportNow, deps());
    }

    public StringBinding scopeSummary() {
        return Bindings.createStringBinding(() -> "Recorte escolhido: " + scopeSummaryText(), deps());
    }

    String scopeSummaryText() {
        var who = clientFilter.get() == ClientFilter.SELECTED_CLIENT
                ? client.get() == null ? "Escolha um cliente" : client.get().displayName() : "Todos os clientes";
        String period;
        var y = id(year.get());
        var m = month.get() == null ? null : month.get().month();
        var range = range();
        period = switch (scope.get()) {
            case ALL_PERIODS -> "todos os períodos";
            case MONTH -> y != null && m != null ? OperationalPeriod.monthLabel(m) + " " + y : "período ainda incompleto";
            case YEAR -> y != null ? "ano de " + y : "período ainda incompleto";
            case RANGE -> range != null ? OperationalPeriod.monthLabel(range[1]) + " " + range[0] + " a "
                    + OperationalPeriod.monthLabel(range[3]) + " " + range[2] : "período ainda incompleto";
        };
        return who + " • " + period;
    }

    private int[] range() {
        var sy = id(startYear.get());
        var ey = id(endYear.get());
        var sm = startMonth.get() == null ? null : startMonth.get().month();
        var em = endMonth.get() == null ? null : endMonth.get().month();
        if (sy == null || ey == null || sm == null || em == null || sm < 1 || sm > 12 || em < 1 || em > 12
                || sy < 1900 || sy > 9999 || ey < 1900 || ey > 9999) {
            return null;
        }
        return new int[] {sy, sm, ey, em};
    }

    private static int key(int y, int m) {
        return y * 12 + m;
    }

    private boolean canExportNow() {
        if (clientFilter.get() == ClientFilter.SELECTED_CLIENT && client.get() == null) {
            return false;
        }
        var y = id(year.get());
        var m = month.get() == null ? null : month.get().month();
        return switch (scope.get()) {
            case ALL_PERIODS -> true;
            case MONTH -> y != null && y >= 1900 && y <= 9999 && m != null && m >= 1 && m <= 12;
            case YEAR -> y != null && y >= 1900 && y <= 9999;
            case RANGE -> {
                var r = range();
                yield r != null && key(r[0], r[1]) <= key(r[2], r[3]);
            }
        };
    }

    DispatchReportFilter buildFilter() {
        var filter = switch (scope.get()) {
            case ALL_PERIODS -> DispatchReportFilter.ALL;
            case MONTH -> DispatchReportFilter.month(id(year.get()), month.get().month());
            case YEAR -> DispatchReportFilter.year(id(year.get()));
            case RANGE -> {
                var r = range();
                yield DispatchReportFilter.range(r[0], r[1], r[2], r[3]);
            }
        };
        if (clientFilter.get() == ClientFilter.SELECTED_CLIENT) {
            filter = filter.withClient(client.get().id(), client.get().displayName());
        }
        return filter;
    }

    /** Subpasta organizada por recorte ({@code GetReportStorageSegment}). */
    static Path storageSegment(DispatchReportFilter filter) {
        var period = switch (filter.scope()) {
            case ALL_PERIODS, CLIENT -> Path.of("todos-os-periodos");
            case MONTH -> Path.of(Integer.toString(filter.year()), "%02d".formatted(filter.month()));
            case YEAR -> Path.of(Integer.toString(filter.year()), "ano-completo");
            case RANGE -> Path.of("intervalos", "%04d-%02d_a_%04d-%02d".formatted(filter.startYear(), filter.startMonth(),
                    filter.endYear(), filter.endMonth()));
        };
        return filter.clientId() == null ? period
                : Path.of("por-cliente", filter.clientId().toString().replace("-", "").substring(0, 12)).resolve(period);
    }

    // ================================================================ linhas

    private boolean itemMatches(DispatchItem item) {
        if (clientFilter.get() == ClientFilter.SELECTED_CLIENT
                && (client.get() == null || !client.get().id().equals(item.clientId()))) {
            return false;
        }
        var ym = yearMonth(item.periodLabel());
        if (ym == null) {
            return scope.get() == Scope.ALL_PERIODS;
        }
        var y = id(year.get());
        var m = month.get() == null ? null : month.get().month();
        return switch (scope.get()) {
            case ALL_PERIODS -> true;
            case MONTH -> Objects.equals(y, ym[0]) && Objects.equals(m, ym[1]);
            case YEAR -> Objects.equals(y, ym[0]);
            case RANGE -> {
                var r = range();
                yield r != null && key(ym[0], ym[1]) >= key(r[0], r[1]) && key(ym[0], ym[1]) <= key(r[2], r[3]);
            }
        };
    }

    /** {@code TryGetYearMonth(string)}: exige ano e mês para contar como competência. */
    static int[] yearMonth(String label) {
        var values = new java.util.ArrayList<Integer>();
        for (var part : (label == null ? "" : label).split("[/\\-:_ ]+")) {
            if (part.matches("\\d+")) {
                values.add(Integer.parseInt(part));
            }
        }
        for (var i = 0; i < values.size(); i++) {
            var v = values.get(i);
            if (v >= 1900 && v <= 9999) {
                if (i >= 1 && values.get(i - 1) >= 1 && values.get(i - 1) <= 12) {
                    return new int[] {v, values.get(i - 1)};
                }
                if (i + 1 < values.size() && values.get(i + 1) >= 1 && values.get(i + 1) <= 12) {
                    return new int[] {v, values.get(i + 1)};
                }
                return null;
            }
        }
        return null;
    }

    private void refreshRows() {
        var items = dispatch.currentItems().stream().filter(this::itemMatches)
                .sorted(Comparator.comparing(DispatchItem::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var latest = dispatch.latestAttempts(items);
        rows.setAll(items.stream().map(item -> {
            var presentation = DispatchOutcomePresenter.present(item, latest.get(item.id()));
            var attachments = item.message() == null ? List.<br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot>of()
                    : item.message().attachments();
            var types = attachments.stream().map(a -> DocumentPresentation.label(a.documentType())).distinct()
                    .sorted(COLLATOR).toList();
            var names = attachments.stream().map(a -> a.fileName()).distinct().sorted(COLLATOR).toList();
            return new CommunicationRow(item.id(), item.clientDisplayName(), item.periodLabel(),
                    "Mensagem " + DispatchModel.shortId(item.id()),
                    "Conjunto " + DispatchModel.shortId(item.groupId()) + " • " + attachments.size() + " documento(s)",
                    attachments.isEmpty() ? "Documentos ainda não preparados"
                            : String.join(", ", types) + " • " + String.join(", ", names),
                    presentation.operationResult(), presentation.deliveryStatus(), presentation.nextAction(),
                    presentation.needsAttention());
        }).toList());
    }

    // ================================================================ clientes

    /** Clientes do histórico (documentos e mensagens) + cadastro atual ({@code LoadReportClientsAsync}). */
    public void loadClients() {
        var selectedId = client.get() == null ? null : client.get().id();
        var candidates = new LinkedHashMap<UUID, ReportClient>();
        documents.documents.stream().filter(d -> d.clientId() != null).forEach(d -> candidates.putIfAbsent(d.clientId(),
                new ReportClient(d.clientId(), historical(d.clientDisplayName()), false)));
        dispatch.items.forEach(i -> candidates.putIfAbsent(i.clientId(),
                new ReportClient(i.clientId(), historical(i.clientDisplayName()), false)));
        applyClients(candidates, selectedId);
        if (catalog == null) {
            return;
        }
        tasks.run(() -> catalog.search(null, null, null, 0, 500).items(), items -> {
            for (ClientListItem item : items) {
                candidates.put(item.id(), new ReportClient(item.id(), item.displayName(), item.isActive()));
            }
            applyClients(candidates, selectedId);
        }, error -> shell.status(ErrorMessages.friendly(error)));
    }

    private static String historical(String name) {
        return name == null || name.isBlank() ? "Cliente sem nome registrado" : name;
    }

    private void applyClients(Map<UUID, ReportClient> candidates, UUID selectedId) {
        clients.setAll(candidates.values().stream().sorted(Comparator.comparing(ReportClient::active).reversed()
                .thenComparing(ReportClient::displayName, COLLATOR)).collect(Collectors.toList()));
        client.set(selectedId == null ? null : clients.stream().filter(c -> c.id().equals(selectedId)).findFirst().orElse(null));
    }

    // ================================================================ comandos

    /** Ao abrir a seção (inclusive por “Continuar para relatórios”). */
    public void open() {
        dispatch.reload();
        loadClients();
        refreshRows();
        shell.status("Relatórios prontos para o recorte " + shell.workPeriodLabel().get() + ".");
    }

    public void export() {
        if (!canExportNow()) {
            shell.status(clientFilter.get() == ClientFilter.SELECTED_CLIENT && client.get() == null
                    ? "Escolha o cliente do relatório."
                    : scope.get() == Scope.RANGE ? "Escolha um intervalo válido, do mês inicial ao mês final."
                    : scope.get() == Scope.MONTH ? "Escolha um ano e um mês válidos para o relatório."
                    : "Complete o período do relatório.");
            return;
        }
        var filter = buildFilter();
        var directory = Path.of(outputDirectory.get()).resolve(storageSegment(filter));
        var summary = scopeSummaryText();
        tasks.run(() -> service.exportReports(directory, filter, CancellationToken.NONE), (DispatchReportResult result) -> {
            lastReportPath.set(result.xlsxPath());
            lastReportPdfPath.set(result.pdfPath() == null ? "O PDF não pôde ser gerado nesta execução." : result.pdfPath());
            dispatch.reload();
            shell.status("Planilha, PDF e " + result.csvPaths().size() + " arquivos auxiliares de “" + summary
                    + "” foram salvos em " + directory + ".");
        }, error -> shell.status(error instanceof DispatchWorkflowException e
                ? "Não foi possível concluir esta etapa: " + e.getMessage() : ErrorMessages.friendly(error)));
    }

    public void chooseOutputDirectory(Path directory) {
        if (directory != null) {
            outputDirectory.set(directory.toString());
            shell.status("Pasta de relatórios definida: " + directory + ".");
        }
    }
}
