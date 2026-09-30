package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresentation;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchOutcomePresenter;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowException;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowService;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountConnectionService;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchBlock;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingBatch;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingBatchState;
import br.com.contadoresassociados.folhas.contracts.dispatch.ProcessingSelectionMode;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
 * Estado e regras da seção Mensagens e envios — porte do trecho de despacho do
 * {@code MainViewModel} (.NET): preparar → conferir → aprovar → concluir, com fila por
 * competência, busca, filtros, confirmação por frase e reconciliação sem reenvio.
 */
public final class DispatchModel {

    /** Filtro da fila ({@code DispatchQueueFilterOption}). */
    public enum QueueFilter {
        PENDING("A fazer"), COMPLETED("Concluídas"), ALL("Todas");

        private final String label;

        QueueFilter(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Collator COLLATOR = Collator.getInstance(OperationalPeriod.PT_BR);
    private static final Set<DispatchItemState> COMPLETED_STATES = Set.of(DispatchItemState.DRAFT_CREATED,
            DispatchItemState.ACCEPTED_BY_PROVIDER, DispatchItemState.RECONCILED, DispatchItemState.COMPLETED);
    private static final Set<DispatchItemState> COMPLETION_STEP_STATES = Set.of(DispatchItemState.APPROVED,
            DispatchItemState.DRAFT_CREATING, DispatchItemState.DRAFT_CREATED, DispatchItemState.SENDING,
            DispatchItemState.ACCEPTED_BY_PROVIDER, DispatchItemState.FAILED, DispatchItemState.AMBIGUOUS,
            DispatchItemState.RECONCILED, DispatchItemState.COMPLETED);
    private static final Set<DispatchItemState> OUTCOME_STATES = Set.of(DispatchItemState.DRAFT_CREATED,
            DispatchItemState.ACCEPTED_BY_PROVIDER, DispatchItemState.RECONCILED, DispatchItemState.COMPLETED,
            DispatchItemState.FAILED, DispatchItemState.AMBIGUOUS);

    private final DispatchWorkflowService service;
    private final EmailAccountConnectionService connection;
    private final DocumentsModel documents;
    private final ShellState shell;
    private final UiTasks tasks;

    // ---------------------------------------------------------------- dados
    public final ObservableList<ProcessingBatch> batches = FXCollections.observableArrayList();
    public final ObservableList<DispatchItem> items = FXCollections.observableArrayList();
    public final ObservableList<DeliveryAttempt> attempts = FXCollections.observableArrayList();
    public final ObservableList<br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAuditEvent> auditEvents =
            FXCollections.observableArrayList();
    public final ObservableList<DocumentDispatchGroup> approvedGroups = FXCollections.observableArrayList();
    public final ObservableList<DispatchItem> visibleItems = FXCollections.observableArrayList();
    public final ObservableList<DispatchAttachmentSnapshot> selectedAttachments = FXCollections.observableArrayList();
    public final ObservableList<DispatchBlock> selectedBlocks = FXCollections.observableArrayList();
    public final ObservableList<DispatchOperationMode> operationModes = FXCollections.observableArrayList();
    public final ObservableList<QueueFilter> queueFilters = FXCollections.observableArrayList(QueueFilter.values());
    public final ObservableList<FakeDeliveryScenario> scenarios = FXCollections.observableArrayList(
            FakeDeliveryScenario.values());

    // ---------------------------------------------------------------- escolhas do operador
    public final ObjectProperty<DispatchItem> selectedItem = new SimpleObjectProperty<>();
    public final ObjectProperty<DispatchOperationMode> operationMode = new SimpleObjectProperty<>(DispatchOperationMode.TEST);
    public final ObjectProperty<FakeDeliveryScenario> scenario = new SimpleObjectProperty<>(FakeDeliveryScenario.SUCCESS);
    public final StringProperty testDestination = new SimpleStringProperty("auditoria@example.invalid");
    public final StringProperty searchText = new SimpleStringProperty("");
    public final ObjectProperty<QueueFilter> queueFilter = new SimpleObjectProperty<>(QueueFilter.PENDING);
    public final StringProperty confirmationPhrase = new SimpleStringProperty("");
    public final StringProperty batchConfirmationPhrase = new SimpleStringProperty("");

    // ---------------------------------------------------------------- textos
    public final StringProperty workspaceSummary = new SimpleStringProperty("Nenhuma mensagem preparada.");
    public final StringProperty connectionStatus = new SimpleStringProperty(
            "Nenhuma conta de e-mail foi conectada nesta instalação.");
    public final BooleanProperty emailAccountConnected = new SimpleBooleanProperty(false);
    public final StringProperty activeProviderKey = new SimpleStringProperty(DispatchWorkflowOptions.FAKE_PROVIDER);
    public final BooleanProperty busy = new SimpleBooleanProperty(false);

    private Runnable showDocuments = () -> { };
    private Runnable showReports = () -> { };
    private java.util.function.Consumer<DeliveryAttempt> reportIncident = attempt -> { };

    public DispatchModel(DispatchWorkflowService service, EmailAccountConnectionService connection,
            DocumentsModel documents, ShellState shell, UiTasks tasks) {
        this.service = Objects.requireNonNull(service);
        this.connection = connection;
        this.documents = Objects.requireNonNull(documents);
        this.shell = Objects.requireNonNull(shell);
        this.tasks = Objects.requireNonNull(tasks);
        refreshAllowedModes();
        ListChangeListener<Object> groupsChanged = change -> refreshApprovedGroups();
        documents.groups.addListener(groupsChanged);
        documents.documents.addListener(groupsChanged);
        shell.selectedYear().addListener((obs, old, now) -> onPeriodChanged());
        shell.selectedMonth().addListener((obs, old, now) -> onPeriodChanged());
        searchText.addListener((obs, old, now) -> refreshVisible());
        queueFilter.addListener((obs, old, now) -> refreshVisible());
        selectedItem.addListener((obs, old, now) -> onSelectedItemChanged(now));
        refreshApprovedGroups();
    }

    public void onShowDocuments(Runnable action) {
        showDocuments = Objects.requireNonNull(action);
    }

    public void onShowReports(Runnable action) {
        showReports = Objects.requireNonNull(action);
    }

    public void onReportIncident(java.util.function.Consumer<DeliveryAttempt> action) {
        reportIncident = Objects.requireNonNull(action);
    }

    /** O grupo escolhido é compartilhado com Documentos (mesmo {@code SelectedReviewGroup} do .NET). */
    public ObjectProperty<DocumentDispatchGroup> selectedGroup() {
        return documents.selectedGroup;
    }

    // ================================================================ derivados

    private Observable[] deps() {
        return new Observable[] {selectedItem, items, attempts, batches, operationMode, approvedGroups, documents.selectedGroup,
                shell.selectedYear(), shell.selectedMonth(), emailAccountConnected, activeProviderKey, searchText, queueFilter,
                visibleItems};
    }

    private BooleanBinding bool(java.util.concurrent.Callable<Boolean> f) {
        return Bindings.createBooleanBinding(f, deps());
    }

    private StringBinding string(java.util.concurrent.Callable<String> f) {
        return Bindings.createStringBinding(f, deps());
    }

    public BooleanBinding hasSelectedItem() {
        return selectedItem.isNotNull();
    }

    public BooleanBinding isPrepareStepCurrent() {
        return selectedItem.isNull();
    }

    public BooleanBinding isReviewStepCurrent() {
        return bool(() -> stateIs(DispatchItemState.BLOCKED));
    }

    public BooleanBinding isApprovalStepCurrent() {
        return bool(() -> stateIs(DispatchItemState.READY_FOR_APPROVAL));
    }

    public BooleanBinding isCompletionStepCurrent() {
        return bool(this::completionStep);
    }

    public BooleanBinding showApprovalPanel() {
        return isApprovalStepCurrent();
    }

    public BooleanBinding showCompletionPanel() {
        return isCompletionStepCurrent();
    }

    public BooleanBinding hasApprovedGroups() {
        return Bindings.isNotEmpty(approvedGroups);
    }

    public BooleanBinding hasSelectedBlocks() {
        return Bindings.isNotEmpty(selectedBlocks);
    }

    public BooleanBinding canSelectOperationMode() {
        return selectedItem.isNull();
    }

    public BooleanBinding isSendMode() {
        return bool(() -> effectiveMode() == DispatchOperationMode.SEND);
    }

    public BooleanBinding canPrepareSelected() {
        return bool(this::canPrepareSelectedNow);
    }

    public BooleanBinding canPrepareBatch() {
        return bool(() -> documents.singleMonthSelected() && !approvedGroups.isEmpty());
    }

    public BooleanBinding canApproveSelected() {
        return bool(this::canApproveSelectedNow);
    }

    public BooleanBinding canApproveSelectedBatch() {
        return bool(this::canApproveBatchNow);
    }

    public BooleanBinding canExecuteSelected() {
        return bool(this::canExecuteSelectedNow);
    }

    public BooleanBinding canExecuteSelectedBatch() {
        return bool(this::canExecuteBatchNow);
    }

    public BooleanBinding showBatchConfirmation() {
        return bool(() -> effectiveMode() == DispatchOperationMode.SEND && canExecuteBatchNow());
    }

    public BooleanBinding canReconcileSelected() {
        return bool(this::canReconcileNow);
    }

    public BooleanBinding hasOutcome() {
        return bool(() -> selectedItem.get() != null && OUTCOME_STATES.contains(selectedItem.get().state()));
    }

    public BooleanBinding showQueueEmptyState() {
        return Bindings.isEmpty(visibleItems);
    }

    public BooleanBinding canOpenReports() {
        return bool(() -> !periodItems().isEmpty());
    }

    public StringBinding selectedReference() {
        return string(() -> selectedItem.get() == null ? "Mensagem" : "Mensagem " + shortId(selectedItem.get().id()));
    }

    public StringBinding selectedSubject() {
        return string(() -> {
            var item = selectedItem.get();
            return item == null || item.message() == null ? "Mensagem selecionada" : item.message().subject();
        });
    }

    public StringBinding selectedBody() {
        return string(() -> {
            var item = selectedItem.get();
            return item == null || item.message() == null ? "" : item.message().textBody();
        });
    }

    public StringBinding selectedRecipients() {
        return string(() -> {
            var item = selectedItem.get();
            return item == null || item.message() == null ? "" : FriendlyText.of(item.message().effectiveTo());
        });
    }

    public StringBinding attachmentsHeader() {
        return string(() -> "Anexos (" + selectedAttachments.size() + ")");
    }

    public StringBinding selectedContextSummary() {
        return string(() -> {
            var item = selectedItem.get();
            if (item == null) {
                return "Escolha uma mensagem para conferir.";
            }
            return item.clientDisplayName() + " • " + item.periodLabel() + " • Conjunto " + shortId(item.groupId()) + " • "
                    + attachmentCount(item) + " anexo(s)";
        });
    }

    public StringBinding modeHelp() {
        return string(() -> {
            var item = selectedItem.get();
            if (item != null) {
                return "Esta mensagem foi preparada como " + FriendlyText.of(item.mode())
                        + ". Para escolher outro modo, use “Preparar outra mensagem”.";
            }
            return switch (operationMode.get() == null ? DispatchOperationMode.TEST : operationMode.get()) {
                case TEST -> "Confere todo o fluxo somente dentro do aplicativo; nenhum e-mail é enviado.";
                case DRAFT -> "Cria a mensagem na conta conectada para uma última conferência, sem enviar.";
                case SEND -> "Solicita o envio somente depois da conferência, aprovação e confirmação final.";
            };
        });
    }

    public BooleanBinding safetyIsWarning() {
        return bool(() -> isSelectedSimulation() || !emailAccountConnected.get());
    }

    public StringBinding safetyTitle() {
        return string(() -> isSelectedSimulation() ? "Simulação local — nenhum e-mail será enviado"
                : !emailAccountConnected.get() ? "Conta de e-mail desconectada"
                : effectiveMode() == DispatchOperationMode.DRAFT ? "Rascunho na conta conectada"
                : "Operação na conta conectada");
    }

    public StringBinding safetyMessage() {
        return string(() -> {
            if (isSelectedSimulation()) {
                return "Você pode preparar, conferir, aprovar e concluir o teste. O resultado fica somente neste aplicativo e não chega a nenhuma caixa postal.";
            }
            if (!emailAccountConnected.get()) {
                return "Conecte a conta em Configurações antes de criar um rascunho ou solicitar um envio real.";
            }
            return switch (effectiveMode()) {
                case TEST -> "O destinatário do cliente será substituído pela caixa controlada de teste.";
                case DRAFT -> "O aplicativo criará um rascunho para nova conferência; não enviará a mensagem.";
                case SEND -> "Somente esta opção solicita o envio depois da aprovação e da confirmação final.";
            };
        });
    }

    public StringBinding actionLabel() {
        return string(() -> switch (effectiveMode()) {
            case TEST -> "Gerar teste seguro";
            case DRAFT -> "Criar rascunho";
            case SEND -> "Enviar agora";
        });
    }

    public StringBinding confirmationGuidance() {
        return string(() -> {
            var item = selectedItem.get();
            if (effectiveMode() != DispatchOperationMode.SEND || item == null || item.message() == null) {
                return "";
            }
            return "Para confirmar somente esta mensagem, digite exatamente: " + expectedPhrase(item);
        });
    }

    public StringBinding batchConfirmationGuidance() {
        return string(() -> {
            var item = selectedItem.get();
            if (item == null || effectiveMode() != DispatchOperationMode.SEND || !canExecuteBatchNow()) {
                return "";
            }
            var approved = approvedItemsOfBatch(item.batchId());
            var attachments = approved.stream().mapToInt(DispatchModel::attachmentCount).sum();
            return "Para concluir as " + approved.size() + " mensagens aprovadas, digite exatamente: CONFIRMAR"
                    + confirmationSuffix() + " LOTE " + approved.size() + " " + attachments;
        });
    }

    public StringBinding outcomeTitle() {
        return string(() -> {
            var outcome = selectedOutcome();
            return outcome == null ? "" : outcome.operationResult();
        });
    }

    public StringBinding outcomeMessage() {
        return string(() -> {
            var outcome = selectedOutcome();
            return outcome == null ? "" : outcome.deliveryStatus() + ". " + outcome.evidence() + ". " + outcome.nextAction();
        });
    }

    public StringBinding queueSummary() {
        return string(() -> {
            var period = periodItems();
            var done = period.stream().filter(i -> isCompleted(i.state())).count();
            var attention = period.stream().filter(i -> i.state() == DispatchItemState.BLOCKED
                    || i.state() == DispatchItemState.FAILED || i.state() == DispatchItemState.AMBIGUOUS).count();
            return (period.size() - done) + " a fazer • " + done + " concluída(s) • " + attention + " precisam de atenção";
        });
    }

    public StringBinding queueEmptyMessage() {
        return string(() -> !searchText.get().strip().isEmpty()
                ? "Nenhuma mensagem corresponde à busca. Confira a referência, o cliente, a competência ou o nome do arquivo."
                : !periodItems().isEmpty()
                        ? "Nenhuma mensagem aparece neste filtro. Escolha outro estado para continuar."
                        : "Ainda não há mensagens preparadas nesta competência. Libere os documentos e prepare o cliente para continuar.");
    }

    // ================================================================ regras

    private boolean stateIs(DispatchItemState state) {
        return selectedItem.get() != null && selectedItem.get().state() == state;
    }

    private boolean completionStep() {
        return selectedItem.get() != null && COMPLETION_STEP_STATES.contains(selectedItem.get().state());
    }

    private DispatchOperationMode effectiveMode() {
        var item = selectedItem.get();
        if (item != null) {
            return item.mode();
        }
        return operationMode.get() == null ? DispatchOperationMode.TEST : operationMode.get();
    }

    private boolean canPrepareSelectedNow() {
        return documents.isVisibleApproved(documents.selectedGroup.get());
    }

    private boolean canApproveSelectedNow() {
        var item = selectedItem.get();
        return item != null && item.state() == DispatchItemState.READY_FOR_APPROVAL && !item.preventsApproval()
                && item.message() != null;
    }

    private boolean canApproveBatchNow() {
        var item = selectedItem.get();
        return item != null && isSelectedPeriodBatchEligible(item.batchId(), i -> i.state() == DispatchItemState.READY_FOR_APPROVAL
                && !i.preventsApproval() && i.message() != null);
    }

    private boolean canExecuteSelectedNow() {
        var item = selectedItem.get();
        if (item == null) {
            return false;
        }
        if (item.state() == DispatchItemState.APPROVED) {
            return true;
        }
        if (item.state() != DispatchItemState.FAILED || item.message() == null || item.approval() == null
                || !item.approval().dispatchFingerprint().equals(item.message().dispatchFingerprint())) {
            return false;
        }
        var fingerprint = item.message().dispatchFingerprint();
        return attempts.stream().filter(a -> a.dispatchItemId().equals(item.id()) && fingerprint.equals(a.dispatchFingerprint()))
                .max(Comparator.comparingInt(DeliveryAttempt::attemptNumber))
                .map(a -> a.state() == DeliveryAttemptState.FAILED_TRANSIENT).orElse(false);
    }

    private boolean canExecuteBatchNow() {
        var item = selectedItem.get();
        return item != null && isSelectedPeriodBatchEligible(item.batchId(), DispatchItem::isApproved);
    }

    private boolean canReconcileNow() {
        var item = selectedItem.get();
        return item != null && (item.state() == DispatchItemState.AMBIGUOUS || attempts.stream()
                .anyMatch(a -> a.dispatchItemId().equals(item.id()) && (a.state() == DeliveryAttemptState.PENDING
                        || a.state() == DeliveryAttemptState.AMBIGUOUS)));
    }

    private boolean isSelectedPeriodBatchEligible(UUID batchId, Predicate<DispatchItem> eligible) {
        if (!documents.singleMonthSelected()) {
            return false;
        }
        var batch = batch(batchId);
        if (batch == null || batch.selectionMode() != ProcessingSelectionMode.BATCH) {
            return false;
        }
        var ids = Set.copyOf(batch.dispatchItemIds());
        var batchItems = items.stream().filter(i -> ids.contains(i.id())).toList();
        return !batchItems.isEmpty() && batchItems.stream().allMatch(i -> matchesSelectedPeriod(i.periodLabel()))
                && batchItems.stream().anyMatch(eligible);
    }

    /** Validação com mensagem ({@code CanUseSelectedPeriodBatch}). */
    private boolean canUseSelectedPeriodBatch(UUID batchId) {
        if (!documents.singleMonthSelected()) {
            shell.status("Para operar várias mensagens, selecione um único mês e ano. Isso impede misturar competências.");
            return false;
        }
        var batchItems = items.stream().filter(i -> i.batchId().equals(batchId)).toList();
        if (batchItems.isEmpty()) {
            shell.status("A sequência selecionada não contém mensagens disponíveis.");
            return false;
        }
        if (batchItems.stream().anyMatch(i -> !matchesSelectedPeriod(i.periodLabel()))) {
            shell.status("Esta sequência antiga reúne mais de uma competência. Processe as mensagens individualmente ou prepare uma nova sequência no mês selecionado.");
            return false;
        }
        return true;
    }

    private ProcessingBatch batch(UUID id) {
        return batches.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    private List<DispatchItem> approvedItemsOfBatch(UUID batchId) {
        var batch = batch(batchId);
        if (batch == null) {
            return List.of();
        }
        var ids = Set.copyOf(batch.dispatchItemIds());
        return items.stream().filter(i -> ids.contains(i.id()) && i.isApproved()).toList();
    }

    private DeliveryAttempt latestAttempt(UUID itemId) {
        return attempts.stream().filter(a -> a.dispatchItemId().equals(itemId))
                .max(Comparator.comparingInt(DeliveryAttempt::attemptNumber)).orElse(null);
    }

    private DispatchOutcomePresentation selectedOutcome() {
        var item = selectedItem.get();
        return item == null ? null : DispatchOutcomePresenter.present(item, latestAttempt(item.id()));
    }

    private boolean isLocalSimulation() {
        return DispatchWorkflowOptions.FAKE_PROVIDER.equals(activeProviderKey.get());
    }

    private boolean isSelectedSimulation() {
        var item = selectedItem.get();
        if (item == null) {
            return isLocalSimulation();
        }
        var latest = latestAttempt(item.id());
        return item.message() == null && latest == null ? isLocalSimulation()
                : DispatchOutcomePresenter.present(item, latest).simulation();
    }

    private String confirmationSuffix() {
        return switch (activeProviderKey.get() == null ? "" : activeProviderKey.get()) {
            case DispatchWorkflowOptions.GMAIL_PROVIDER -> " GMAIL";
            case DispatchWorkflowOptions.GRAPH_PROVIDER -> " GRAPH";
            default -> "";
        };
    }

    private String expectedPhrase(DispatchItem item) {
        return "CONFIRMAR" + confirmationSuffix() + " " + attachmentCount(item);
    }

    static int attachmentCount(DispatchItem item) {
        return item.message() == null ? 0 : item.message().attachments().size();
    }

    static boolean isCompleted(DispatchItemState state) {
        return COMPLETED_STATES.contains(state);
    }

    /** {@code #ABC123}: seis primeiros dígitos hexadecimais em maiúsculas ({@code ShortIdentifierConverter}). */
    public static String shortId(UUID id) {
        return id == null ? "" : "#" + id.toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    // ================================================================ fila

    /** Mensagem atual por conjunto: a mais recente não cancelada ({@code CurrentDispatchItems}). */
    List<DispatchItem> currentItems() {
        return items.stream().filter(i -> i.state() != DispatchItemState.CANCELLED)
                .collect(Collectors.groupingBy(DispatchItem::groupId, java.util.LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(list -> list.stream().max(Comparator.comparingLong(DispatchItem::revision)
                        .thenComparing(DispatchItem::createdAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(DispatchItem::updatedAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(DispatchItem::id)).orElseThrow())
                .toList();
    }

    private List<DispatchItem> periodItems() {
        return currentItems().stream().filter(i -> matchesSelectedPeriod(i.periodLabel())).toList();
    }

    private boolean matchesFilter(DispatchItem item) {
        return switch (queueFilter.get() == null ? QueueFilter.ALL : queueFilter.get()) {
            case PENDING -> !isCompleted(item.state());
            case COMPLETED -> isCompleted(item.state());
            case ALL -> true;
        };
    }

    private boolean matchesSearch(DispatchItem item) {
        var search = searchText.get() == null ? "" : searchText.get().strip();
        if (search.isEmpty()) {
            return true;
        }
        var normalized = search.replaceFirst("^#+", "").strip().toLowerCase(Locale.ROOT);
        var itemHex = item.id().toString().replace("-", "");
        var groupHex = item.groupId().toString().replace("-", "");
        var parts = new java.util.ArrayList<String>(List.of(Objects.toString(item.clientDisplayName(), ""),
                Objects.toString(item.periodLabel(), ""), itemHex, groupHex, "Mensagem " + shortId(item.id()),
                "Conjunto " + shortId(item.groupId())));
        if (item.message() != null) {
            parts.add(Objects.toString(item.message().subject(), ""));
            item.message().attachments().forEach(a -> parts.add(a.fileName() + " " + DocumentPresentation.label(a.documentType())));
        }
        var searchable = String.join(" ", parts).toLowerCase(Locale.ROOT);
        return searchable.contains(search.toLowerCase(Locale.ROOT)) || searchable.contains(normalized);
    }

    private void refreshVisible() {
        var selected = selectedItem.get();
        visibleItems.setAll(periodItems().stream().filter(i -> matchesFilter(i) && matchesSearch(i))
                .sorted(Comparator.comparing(DispatchItem::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());
        // Reinsere a seleção (a lista troca as instâncias).
        if (selected != null && !Objects.equals(selectedItem.get(), selected)) {
            selectedItem.set(selected);
        }
    }

    private void refreshApprovedGroups() {
        var selected = documents.selectedGroup.get();
        approvedGroups.setAll(documents.visibleApprovedGroups());
        if (selected != null && documents.selectedGroup.get() != selected) {
            documents.selectedGroup.set(selected);
        }
    }

    private void onPeriodChanged() {
        refreshApprovedGroups();
        refreshVisible();
        var item = selectedItem.get();
        if (item != null && !matchesSelectedPeriod(item.periodLabel())) {
            selectedItem.set(null);
        }
    }

    private void onSelectedItemChanged(DispatchItem item) {
        confirmationPhrase.set("");
        batchConfirmationPhrase.set("");
        if (item != null && operationMode.get() != item.mode()) {
            operationMode.set(item.mode());
        }
        selectedAttachments.setAll(item == null || item.message() == null ? List.of() : item.message().attachments());
        selectedBlocks.setAll(item == null ? List.of() : item.blocks());
    }

    private void refreshAllowedModes() {
        var options = service.options();
        List<DispatchOperationMode> modes;
        if (options.pilotModeEnabled()) {
            modes = java.util.Arrays.stream(DispatchOperationMode.values()).filter(m -> switch (m) {
                case TEST -> options.pilotAllowTest();
                case DRAFT -> options.pilotAllowDraft();
                case SEND -> options.pilotAllowSend();
            }).toList();
        } else if (options.productionRolloutEnforced() && !options.productionRolloutReady()) {
            modes = List.of(DispatchOperationMode.TEST, DispatchOperationMode.DRAFT);
        } else {
            modes = List.of(DispatchOperationMode.values());
        }
        operationModes.setAll(modes);
        if (!modes.contains(operationMode.get()) && !modes.isEmpty()) {
            operationMode.set(modes.getFirst());
        }
    }

    /** Mesma leitura de competência por rótulo do .NET ({@code TryGetYearMonth(string)}). */
    boolean matchesSelectedPeriod(String label) {
        var values = new java.util.ArrayList<Integer>();
        for (var part : (label == null ? "" : label).split("[/\\-:_ ]+")) {
            if (part.matches("\\d+")) {
                values.add(Integer.parseInt(part));
            }
        }
        var yearIndex = -1;
        for (var i = 0; i < values.size(); i++) {
            if (values.get(i) >= 1900 && values.get(i) <= 9999) {
                yearIndex = i;
                break;
            }
        }
        var has = yearIndex >= 0;
        var year = has ? values.get(yearIndex) : 0;
        var month = 0;
        if (has) {
            if (yearIndex >= 1 && values.get(yearIndex - 1) >= 1 && values.get(yearIndex - 1) <= 12) {
                month = values.get(yearIndex - 1);
            } else if (yearIndex + 1 < values.size() && values.get(yearIndex + 1) >= 1 && values.get(yearIndex + 1) <= 12) {
                month = values.get(yearIndex + 1);
            }
        }
        var selectedYear = shell.selectedYear().get() == null ? null : shell.selectedYear().get().year();
        if (selectedYear != null && (!has || year != selectedYear)) {
            return false;
        }
        var selectedMonth = shell.selectedMonth().get() == null ? null : shell.selectedMonth().get().month();
        if (selectedMonth == null) {
            return true;
        }
        return selectedMonth == 0 ? !has || month == 0 : has && month == selectedMonth;
    }

    // ================================================================ carregar

    /** Ao entrar na seção: recarrega a fila (o serviço invalida aprovações que ficaram desatualizadas). */
    public void refresh() {
        refreshApprovedGroups();
        reload();
    }

    public void reload() {
        refreshConnection();
        run(() -> service.load(CancellationToken.NONE), ws -> apply(ws, null), null);
    }

    private void refreshConnection() {
        if (connection == null) {
            return;
        }
        tasks.run(connection::status, status -> {
            activeProviderKey.set(status.providerKey());
            emailAccountConnected.set(status.connected());
            connectionStatus.set(status.connected()
                    ? DispatchWorkflowOptions.FAKE_PROVIDER.equals(status.providerKey())
                            ? "Simulação local ativa; nenhuma mensagem sai desta máquina."
                            : "Conta " + providerName(status.providerKey()) + " conectada como " + status.displayName() + "."
                    : status.configured() ? "Conta " + providerName(status.providerKey()) + " configurada, mas desconectada."
                    : "Integração " + providerName(status.providerKey()) + " ainda não configurada nesta instalação.");
        }, error -> connectionStatus.set("Nenhuma conta de e-mail foi conectada nesta instalação."));
    }

    private static String providerName(String key) {
        return switch (key == null ? "" : key) {
            case DispatchWorkflowOptions.GMAIL_PROVIDER -> "Gmail";
            case DispatchWorkflowOptions.GRAPH_PROVIDER -> "Microsoft 365";
            default -> "local";
        };
    }

    /** {@code ApplyDispatchWorkspace}: mantém a seleção e ajusta busca/filtro para ela aparecer. */
    private void apply(DispatchWorkspace workspace, UUID preferredItemId) {
        var selectedId = preferredItemId != null ? preferredItemId : selectedItem.get() == null ? null : selectedItem.get().id();
        batches.setAll(workspace.batches().stream()
                .sorted(Comparator.comparing(ProcessingBatch::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
        attempts.setAll(workspace.attempts().stream()
                .sorted(Comparator.comparing(DeliveryAttempt::startedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
        items.setAll(workspace.items().stream()
                .sorted(Comparator.comparing(DispatchItem::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
        auditEvents.setAll(workspace.auditEvents());
        var current = currentItems();
        var byRecent = Comparator.comparing(DispatchItem::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()));
        var next = current.stream().filter(i -> i.id().equals(selectedId) && matchesSelectedPeriod(i.periodLabel())).findFirst()
                .or(() -> current.stream().filter(i -> matchesSelectedPeriod(i.periodLabel()) && matchesFilter(i) && matchesSearch(i))
                        .sorted(byRecent).findFirst())
                .or(() -> current.stream().filter(i -> matchesSelectedPeriod(i.periodLabel())).sorted(byRecent).findFirst())
                .orElse(null);
        if (next != null) {
            if (!matchesSearch(next)) {
                searchText.set("");
            }
            if (!matchesFilter(next)) {
                queueFilter.set(isCompleted(next.state()) ? QueueFilter.COMPLETED : QueueFilter.PENDING);
            }
        }
        selectedItem.set(null);
        refreshVisible();
        selectedItem.set(next);
        workspaceSummary.set("Mensagens atuais: " + current.size() + " • Operações registradas: " + workspace.attempts().size()
                + " • Para conferir: " + workspace.recoveryRequiredCount());
    }

    // ================================================================ comandos

    public void prepareSelected() {
        var group = documents.selectedGroup.get();
        if (!canPrepareSelectedNow()) {
            shell.status("Selecione um conjunto liberado para preparar a mensagem deste cliente.");
            return;
        }
        var groupId = group.id();
        var existing = items.stream().map(DispatchItem::id).collect(Collectors.toSet());
        var request = new DispatchWorkflowService.PrepareRequest(List.of(groupId), ProcessingSelectionMode.INDIVIDUAL,
                operationMode.get(), testDestination.get(), scenario.get());
        run(() -> service.prepare(request, CancellationToken.NONE), ws -> {
            var prepared = ws.items().stream().filter(i -> i.groupId().equals(groupId) && i.state() != DispatchItemState.CANCELLED
                    && !existing.contains(i.id())).max(Comparator.comparing(DispatchItem::createdAtUtc)).map(DispatchItem::id)
                    .orElse(null);
            searchText.set("");
            queueFilter.set(QueueFilter.PENDING);
            apply(ws, prepared);
            shell.status(selectedReference().get() + " preparada. Confira destinatários, texto e anexos antes de aprovar.");
        }, null);
    }

    public void prepareAnother() {
        selectedItem.set(null);
        confirmationPhrase.set("");
        batchConfirmationPhrase.set("");
        shell.status("Escolha os documentos liberados e o modo da nova mensagem.");
    }

    public void prepareApprovedBatch() {
        if (!documents.singleMonthSelected()) {
            shell.status("Para preparar vários clientes, selecione um único mês e ano. Isso impede misturar competências.");
            return;
        }
        var groupIds = approvedGroups.stream().map(DocumentDispatchGroup::id).toList();
        if (groupIds.isEmpty()) {
            shell.status("Não há conjuntos liberados para preparar. Documentos bloqueados permanecem excluídos.");
            return;
        }
        var existing = items.stream().map(DispatchItem::id).collect(Collectors.toSet());
        var ids = new HashSet<>(groupIds);
        var request = new DispatchWorkflowService.PrepareRequest(groupIds, ProcessingSelectionMode.BATCH, operationMode.get(),
                testDestination.get(), scenario.get());
        run(() -> service.prepare(request, CancellationToken.NONE), ws -> {
            var prepared = ws.items().stream().filter(i -> ids.contains(i.groupId()) && i.state() != DispatchItemState.CANCELLED
                    && !existing.contains(i.id()))
                    .sorted(Comparator.comparing(DispatchItem::clientDisplayName, COLLATOR)
                            .thenComparing(DispatchItem::periodLabel, COLLATOR))
                    .map(DispatchItem::id).findFirst().orElse(null);
            searchText.set("");
            queueFilter.set(QueueFilter.PENDING);
            apply(ws, prepared);
            shell.status(groupIds.size() + " conjunto(s) foram preparados em mensagens separadas; documentos bloqueados ficaram de fora. "
                    + "Comece por " + selectedReference().get() + ".");
        }, null);
    }

    public void approveSelected() {
        var item = selectedItem.get();
        if (item == null || !canApproveSelectedNow()) {
            shell.status("Selecione uma mensagem pronta para aprovação.");
            return;
        }
        run(() -> service.approve(item.id(), CancellationToken.NONE), ws -> {
            apply(ws, null);
            shell.status("Mensagem aprovada. Alterações posteriores exigirão uma nova conferência.");
        }, null);
    }

    public void approveSelectedBatch() {
        var item = selectedItem.get();
        if (item == null) {
            shell.status("Selecione uma mensagem desta sequência.");
            return;
        }
        if (!canUseSelectedPeriodBatch(item.batchId())) {
            return;
        }
        if (!canApproveBatchNow()) {
            shell.status("Não há mensagens prontas para aprovação nesta sequência de " + shell.workPeriodLabel().get() + ".");
            return;
        }
        run(() -> service.approveBatch(item.batchId(), CancellationToken.NONE), ws -> {
            apply(ws, null);
            shell.status("As mensagens prontas deste mês foram aprovadas; as que precisam de correção ficaram de fora.");
        }, null);
    }

    public void executeSelected() {
        var item = selectedItem.get();
        if (item == null || !canExecuteSelectedNow()) {
            shell.status("Selecione uma mensagem aprovada ou uma tentativa com falha temporária confirmada.");
            return;
        }
        var phrase = confirmationPhrase.get();
        run(() -> service.execute(item.id(), phrase, CancellationToken.NONE), ws -> {
            apply(ws, null);
            var outcome = selectedOutcome();
            shell.status(outcome != null ? outcome.operationResult() + ". " + outcome.deliveryStatus() + ". " + outcome.nextAction()
                    : "A operação foi registrada. Confira o resultado antes de qualquer nova tentativa.");
        }, null);
    }

    public void executeSelectedBatch() {
        var item = selectedItem.get();
        if (item == null) {
            shell.status("Selecione uma mensagem aprovada desta sequência.");
            return;
        }
        var batchId = item.batchId();
        if (!canUseSelectedPeriodBatch(batchId)) {
            return;
        }
        if (!canExecuteBatchNow()) {
            shell.status("Não há mensagens aprovadas para concluir nesta sequência de " + shell.workPeriodLabel().get() + ".");
            return;
        }
        var phrase = batchConfirmationPhrase.get();
        run(() -> service.executeBatch(batchId, phrase, CancellationToken.NONE), ws -> {
            apply(ws, null);
            var state = ws.batches().stream().filter(b -> b.id().equals(batchId)).map(ProcessingBatch::state).findFirst()
                    .orElse(ProcessingBatchState.PROCESSING);
            shell.status(switch (state) {
                case COMPLETED -> describeCompletedBatch(batchId);
                case COMPLETED_WITH_ERRORS -> "Sequência concluída com falhas. Confira as mensagens marcadas antes de qualquer nova tentativa.";
                case RECOVERY_REQUIRED -> "Sequência pausada porque um resultado ficou incerto. Use Conferir situação; não repita o envio.";
                case CANCELLED -> "Sequência cancelada. Nenhuma nova operação será iniciada.";
                default -> "Sequência ainda em processamento. Aguarde a atualização antes de iniciar outra ação.";
            });
        }, null);
    }

    private String describeCompletedBatch(UUID batchId) {
        var outcomes = items.stream().filter(i -> i.batchId().equals(batchId) && isCompleted(i.state()))
                .map(i -> DispatchOutcomePresenter.present(i, latestAttempt(i.id()))).toList();
        if (outcomes.isEmpty()) {
            return "Sequência concluída. Confira cada resultado antes de qualquer nova tentativa.";
        }
        if (outcomes.stream().allMatch(DispatchOutcomePresentation::simulation)) {
            return outcomes.size() + " simulação(ões) concluída(s) somente neste aplicativo. Nenhum e-mail real foi enviado.";
        }
        if (outcomes.stream().noneMatch(DispatchOutcomePresentation::simulation)) {
            return outcomes.size() + " operação(ões) registrada(s) no serviço de e-mail. Isso não comprova entrega ou leitura; confira os resultados sem repetir o envio.";
        }
        return "Sequência concluída com registros locais e externos. Confira o resultado de cada mensagem; nenhum aceite técnico comprova entrega ou leitura.";
    }

    public void reconcileSelected() {
        var item = selectedItem.get();
        if (item == null || !canReconcileNow()) {
            shell.status("Selecione um item pendente ou ambíguo.");
            return;
        }
        run(() -> service.reconcile(item.id(), CancellationToken.NONE), ws -> {
            apply(ws, null);
            shell.status("Reconciliação concluída sem repetir a operação de envio.");
        }, null);
    }

    public void reportIncident() {
        var item = selectedItem.get();
        if (item == null) {
            shell.status("Selecione uma mensagem com tentativa registrada para relatar uma ocorrência.");
            return;
        }
        var attempt = attempts.stream().filter(a -> a.dispatchItemId().equals(item.id()))
                .max(Comparator.comparing(DeliveryAttempt::startedAtUtc)).orElse(null);
        reportIncident.accept(attempt);
        shell.status(attempt == null ? "Ainda não há tentativa registrada para esta mensagem."
                : "Descreva apenas o necessário; dados fiscais, e-mails e segredos serão redigidos.");
    }

    public void backToDocuments() {
        showDocuments.run();
    }

    public void continueToReports() {
        showReports.run();
    }

    /** Itens atuais da competência (para Relatórios). */
    public List<DispatchItem> periodDispatchItems() {
        return periodItems();
    }

    public Map<UUID, DeliveryAttempt> latestAttempts(Collection<DispatchItem> of) {
        return of.stream().map(i -> latestAttempt(i.id())).filter(Objects::nonNull)
                .collect(Collectors.toMap(DeliveryAttempt::dispatchItemId, a -> a, (a, b) -> a));
    }

    private <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess, Runnable onError) {
        busy.set(true);
        tasks.run(work, value -> {
            busy.set(false);
            onSuccess.accept(value);
        }, error -> {
            busy.set(false);
            fail(error);
            if (onError != null) {
                onError.run();
            }
        });
    }

    private void fail(Throwable error) {
        shell.status(switch (error) {
            case DispatchWorkflowException e -> "Não foi possível concluir esta etapa: " + e.getMessage();
            default -> ErrorMessages.friendly(error);
        });
    }
}
