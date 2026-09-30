package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import br.com.contadoresassociados.folhas.application.documents.DocumentPeriodParser;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewException;
import br.com.contadoresassociados.folhas.application.documents.DocumentReviewService;
import br.com.contadoresassociados.folhas.application.documents.recognition.DocumentImportException;
import br.com.contadoresassociados.folhas.application.documents.recognition.RecognitionPorts;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionCandidate;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionMethod;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriod;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriodKind;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewGroupState;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Estado e regras da seção Documentos — porte do trecho de revisão do {@code MainViewModel}
 * (.NET), com as melhorias:
 * <ul>
 *   <li>6.20: todos os PDFs da seleção são gravados e revalidados numa única operação;</li>
 *   <li>reconhecimento e cópia ao acervo rodam fora da thread da interface, com interrupção
 *       segura (o que já foi concluído permanece);</li>
 *   <li>cópia ao acervo é atômica (arquivo temporário + move) e desfeita se a gravação falhar.</li>
 * </ul>
 */
public final class DocumentsModel {

    /** Opção de união manual (equivalente a {@code ManualGroupOption}). */
    public record MergeOption(UUID groupId, String label, String detail) {
        @Override
        public String toString() {
            return label + " — " + detail;
        }
    }

    private record Staged(Path path, boolean created) {
    }

    private record ImportOutcome(DocumentReviewWorkspace workspace, int imported, int alreadyImported,
            List<String> failures, List<String> importedHashes, boolean cancelled, int distinctPeriods) {
    }

    private static final int MIN_REASON = 10;
    private static final Collator COLLATOR = Collator.getInstance(OperationalPeriod.PT_BR);

    private final DocumentReviewService review;
    private final RecognitionPorts.DocumentExtractor recognition;
    private final ShellState shell;
    private final UiTasks tasks;
    private final Path archiveDirectory;
    private final DocumentPeriodParser periodParser = new DocumentPeriodParser();

    public final ObservableList<ReviewDocument> documents = FXCollections.observableArrayList();
    public final ObservableList<DocumentDispatchGroup> groups = FXCollections.observableArrayList();
    public final ObservableList<ReviewDocument> visibleDocuments = FXCollections.observableArrayList();
    public final ObjectProperty<ReviewDocument> selectedDocument = new SimpleObjectProperty<>();
    public final ObjectProperty<DocumentDispatchGroup> selectedGroup = new SimpleObjectProperty<>();
    public final ObservableList<ReviewDocument> selectedGroupDocuments = FXCollections.observableArrayList();
    public final ObservableList<MergeOption> mergeOptions = FXCollections.observableArrayList();
    public final ObjectProperty<MergeOption> selectedMergeOption = new SimpleObjectProperty<>();

    public final StringProperty reviewWorkspaceSummary = new SimpleStringProperty("Carregando revisão local recuperável…");
    public final StringProperty documentImportSummary = new SimpleStringProperty(
            "Nenhum arquivo novo nesta sessão. PDFs: até 25 MB e 100 páginas por documento.");
    public final BooleanProperty importPanelExpanded = new SimpleBooleanProperty(true);
    public final BooleanProperty correctionPanelExpanded = new SimpleBooleanProperty(false);
    public final BooleanProperty recognizing = new SimpleBooleanProperty(false);
    public final IntegerProperty importProgress = new SimpleIntegerProperty();
    public final IntegerProperty importTotal = new SimpleIntegerProperty();
    public final StringProperty inputFolder = new SimpleStringProperty("");
    public final BooleanProperty includeSubfolders = new SimpleBooleanProperty(false);

    public final ObjectProperty<ClientResolutionCandidate> overrideCandidate = new SimpleObjectProperty<>();
    public final StringProperty overrideReason = new SimpleStringProperty("");
    public final ObjectProperty<OperationalPeriod.YearOption> correctionYear = new SimpleObjectProperty<>();
    public final ObjectProperty<OperationalPeriod.MonthOption> correctionMonth = new SimpleObjectProperty<>();
    public final StringProperty correctionReason = new SimpleStringProperty("");
    public final BooleanProperty removalConfirmation = new SimpleBooleanProperty(false);
    public final StringProperty removalReason = new SimpleStringProperty("");
    public final StringProperty splitReason = new SimpleStringProperty("");
    public final StringProperty mergeReason = new SimpleStringProperty("");

    private volatile CancellationToken importCancellation;
    private Consumer<UUID> openClient = id -> { };
    private Runnable continueToDispatch = () -> { };
    private boolean loaded;

    public DocumentsModel(DocumentReviewService review, RecognitionPorts.DocumentExtractor recognition, ShellState shell,
            UiTasks tasks, Path archiveDirectory) {
        this.review = review;
        this.recognition = recognition;
        this.shell = shell;
        this.tasks = tasks;
        this.archiveDirectory = archiveDirectory;
        shell.selectedYear().addListener((obs, old, now) -> refreshPeriod());
        shell.selectedMonth().addListener((obs, old, now) -> refreshPeriod());
        selectedDocument.addListener((obs, old, now) -> onDocumentSelected(now));
        selectedGroup.addListener((obs, old, now) -> refreshGroupDerived());
    }

    public void onOpenClient(Consumer<UUID> action) {
        openClient = Objects.requireNonNull(action);
    }

    public void onContinueToDispatch(Runnable action) {
        continueToDispatch = Objects.requireNonNull(action);
    }

    // ================================================================ derivados (bindings)

    private Observable[] deps() {
        return new Observable[] {documents, groups, visibleDocuments, selectedDocument, selectedGroup, shell.selectedYear(),
                shell.selectedMonth()};
    }

    public BooleanBinding hasSelection() {
        return selectedDocument.isNotNull();
    }

    public BooleanBinding isImportStepCurrent() {
        return Bindings.isEmpty(visibleDocuments);
    }

    public BooleanBinding isApprovalStepCurrent() {
        return Bindings.createBooleanBinding(() -> visibleGroups().anyMatch(g -> g.state() == ReviewGroupState.READY_FOR_REVIEW
                || g.state() == ReviewGroupState.APPROVED), deps());
    }

    public BooleanBinding isReviewStepCurrent() {
        return Bindings.createBooleanBinding(() -> !visibleDocuments.isEmpty() && !isApprovalStepCurrent().get(), deps());
    }

    public StringBinding importProgressText() {
        return Bindings.createStringBinding(() -> importTotal.get() == 0 ? "Aguardando documentos"
                : "Processando " + importProgress.get() + " de " + importTotal.get(), importProgress, importTotal);
    }

    public StringBinding inputFolderDisplay() {
        return Bindings.createStringBinding(() -> inputFolder.get().isBlank()
                ? "Nenhuma pasta de entrada selecionada" : inputFolder.get(), inputFolder);
    }

    public BooleanBinding hasInputFolder() {
        return Bindings.createBooleanBinding(() -> !inputFolder.get().isBlank(), inputFolder);
    }

    public int hiddenDocumentCount() {
        return Math.max(0, documents.size() - visibleDocuments.size());
    }

    public BooleanBinding hasHiddenDocuments() {
        return Bindings.createBooleanBinding(() -> hiddenDocumentCount() > 0, documents, visibleDocuments);
    }

    public StringBinding hiddenDocumentsText() {
        return Bindings.createStringBinding(() -> hiddenDocumentCount() + " documento(s) estão fora da competência selecionada.",
                documents, visibleDocuments);
    }

    public BooleanBinding showEmptyState() {
        return Bindings.createBooleanBinding(() -> !documents.isEmpty() && visibleDocuments.isEmpty(), documents, visibleDocuments);
    }

    public BooleanBinding hasVisibleGroups() {
        return Bindings.createBooleanBinding(() -> visibleGroups().findAny().isPresent(), deps());
    }

    public StringBinding selectedFileName() {
        return Bindings.createStringBinding(() -> selectedDocument.get() == null ? "" : selectedDocument.get().fileName(), selectedDocument);
    }

    public StringBinding selectedTypeLabel() {
        return Bindings.createStringBinding(() -> selectedDocument.get() == null ? ""
                : DocumentPresentation.label(selectedDocument.get().documentType()), selectedDocument);
    }

    public StringBinding selectedStateLabel() {
        return Bindings.createStringBinding(() -> selectedDocument.get() == null ? "" : FriendlyText.of(selectedDocument.get().state()),
                selectedDocument);
    }

    public StringBinding selectedPendingLabel() {
        return Bindings.createStringBinding(() -> selectedDocument.get() == null ? ""
                : "Pendências: " + selectedDocument.get().blockingFindingCount(), selectedDocument);
    }

    public boolean selectedHasInactiveClientIssue() {
        var d = selectedDocument.get();
        return d != null && (d.resolutionBlockers().contains("client.inactive")
                || d.findings().stream().anyMatch(f -> !f.isResolved() && "client.inactive".equals(f.ruleCode())));
    }

    public BooleanBinding hasInactiveClientIssue() {
        return Bindings.createBooleanBinding(this::selectedHasInactiveClientIssue, selectedDocument);
    }

    public BooleanBinding hasClientAlternatives() {
        return Bindings.createBooleanBinding(() -> !selectedHasInactiveClientIssue() && selectedDocument.get() != null
                && !selectedDocument.get().clientAlternatives().isEmpty(), selectedDocument);
    }

    public BooleanBinding canRestorePeriod() {
        return Bindings.createBooleanBinding(() -> selectedDocument.get() != null && selectedDocument.get().periodOverride() != null,
                selectedDocument);
    }

    public StringBinding clientSummary() {
        return Bindings.createStringBinding(() -> {
            var d = selectedDocument.get();
            if (d == null) {
                return "Selecione um documento para conferir o cliente.";
            }
            if (d.clientId() == null) {
                return "Cliente ainda não identificado. Resolva esta pendência antes de liberar o documento.";
            }
            var name = d.clientDisplayName();
            var tax = d.clientTaxIdMasked();
            var hasName = name != null && !name.isEmpty();
            var hasTax = tax != null && !tax.isEmpty();
            if (d.resolutionMethod() == ClientResolutionMethod.MANUAL_OVERRIDE && hasName && hasTax) {
                return "Cliente confirmado por você: " + name + " • " + tax;
            }
            if (hasName && hasTax) {
                return "Cliente identificado: " + name + " • " + tax;
            }
            return hasName ? "Cliente identificado: " + name : "Cliente identificado pelo cadastro local.";
        }, selectedDocument);
    }

    public StringBinding recognitionMethodSummary() {
        return Bindings.createStringBinding(() -> {
            var d = selectedDocument.get();
            if (d == null) {
                return "Selecione um documento para ver como o cliente foi reconhecido.";
            }
            return d.clientId() == null ? "Nenhuma associação foi feita automaticamente."
                    : "Como foi identificado: " + FriendlyText.of(d.resolutionMethod()) + ".";
        }, selectedDocument);
    }

    public StringBinding groupingSummary() {
        return Bindings.createStringBinding(() -> {
            var d = selectedDocument.get();
            if (d == null) {
                return "Selecione um documento para ver como ele será organizado.";
            }
            if (d.clientId() == null) {
                return "Este documento ainda não foi organizado porque o cliente não pôde ser confirmado.";
            }
            var g = selectedGroup.get();
            if (g == null) {
                var same = visibleDocuments.stream().filter(x -> d.clientId().equals(x.clientId())).count();
                return same == 1
                        ? "O cliente foi identificado, mas este documento ainda precisa das correções indicadas antes de seguir."
                        : "Há " + same + " documentos deste cliente na competência. Eles serão reunidos quando as pendências e as regras contábeis permitirem.";
            }
            var count = g.documentIds().size();
            return count == 1
                    ? "Este documento foi organizado automaticamente para " + g.clientDisplayName() + ", na competência " + g.periodLabel() + "."
                    : count + " documentos de " + g.clientDisplayName() + " foram organizados automaticamente para seguirem juntos na competência " + g.periodLabel() + ".";
        }, deps());
    }

    public StringBinding groupStatusSummary() {
        return Bindings.createStringBinding(() -> selectedGroup.get() == null ? "Aguardando a correção deste documento."
                : selectedGroup.get().approvalSummary(), selectedGroup);
    }

    public StringBinding guidance() {
        return Bindings.createStringBinding(() -> {
            var d = selectedDocument.get();
            if (d == null) {
                return "Selecione um documento à esquerda para ver o que falta e as ações disponíveis.";
            }
            if (d.state() == ReviewDocumentState.DUPLICATE) {
                return "Este conteúdo já foi importado. Confira o arquivo original e retire esta cópia da revisão se ela entrou por engano.";
            }
            if (selectedHasInactiveClientIssue()) {
                return "O cadastro associado está inativo. Abra-o e clique em Reativar agora; ao voltar, a análise será atualizada.";
            }
            if (!d.resolutionBlockers().isEmpty()) {
                return "O cliente não pôde ser confirmado. Escolha uma alternativa autenticada ou abra o cadastro para corrigir os identificadores.";
            }
            if (d.period() == null || d.period().kind() == DocumentPeriodKind.UNKNOWN) {
                return "A competência não foi reconhecida. Informe o mês e o ano abaixo e registre o motivo da correção.";
            }
            if (d.blockingFindingCount() > 0) {
                return "Leia cada pendência abaixo. As correções disponíveis aparecem neste mesmo painel.";
            }
            return "Documento conferido. Se o conjunto estiver pronto, libere-o para preparar a mensagem.";
        }, selectedDocument);
    }

    public StringBinding groupViewerHeader() {
        return Bindings.createStringBinding(() -> selectedGroup.get() == null ? "Ver o conjunto completo"
                : "Ver o conjunto completo (" + selectedGroup.get().documentIds().size() + " documento(s))", selectedGroup);
    }

    public BooleanBinding hasGroupDocuments() {
        return Bindings.isNotEmpty(selectedGroupDocuments);
    }

    public BooleanBinding canApproveSelectedGroup() {
        return Bindings.createBooleanBinding(() -> {
            var g = selectedGroup.get();
            return g != null && g.state() == ReviewGroupState.READY_FOR_REVIEW && !g.preventsApproval() && !g.documentIds().isEmpty();
        }, selectedGroup);
    }

    public StringBinding setApprovalLabel() {
        return Bindings.createStringBinding(() -> selectedGroup.get() == null ? "Liberar este conjunto"
                : "Liberar este conjunto (" + selectedGroup.get().documentIds().size() + " documento(s))", selectedGroup);
    }

    private List<DocumentDispatchGroup> readyGroupsForSelectedClient() {
        var clientId = selectedDocument.get() != null && selectedDocument.get().clientId() != null ? selectedDocument.get().clientId()
                : selectedGroup.get() != null ? selectedGroup.get().clientId() : null;
        if (clientId == null) {
            return List.of();
        }
        return groups.stream().filter(g -> clientId.equals(g.clientId()) && g.state() == ReviewGroupState.READY_FOR_REVIEW
                && !g.preventsApproval()).toList();
    }

    public BooleanBinding showClientApproval() {
        return Bindings.createBooleanBinding(() -> readyGroupsForSelectedClient().size() > 1, deps());
    }

    public StringBinding clientApprovalLabel() {
        return Bindings.createStringBinding(() -> {
            var ready = readyGroupsForSelectedClient();
            var docs = ready.stream().flatMap(g -> g.documentIds().stream()).distinct().count();
            var periods = ready.stream().map(g -> g.periodLabel().toLowerCase(Locale.ROOT)).distinct().count();
            return "Liberar este cliente (" + ready.size() + " conjuntos / " + docs + " documentos / " + periods + " competências)";
        }, deps());
    }

    public StringBinding clientApprovalHelp() {
        return Bindings.createStringBinding(() -> "Os " + readyGroupsForSelectedClient().size()
                + " conjuntos continuarão separados por competência e originarão mensagens independentes; erros, bloqueios e repetidos ficam de fora.",
                deps());
    }

    private List<DocumentDispatchGroup> readyVisibleGroups() {
        return visibleGroups().filter(g -> g.state() == ReviewGroupState.READY_FOR_REVIEW && !g.preventsApproval()).toList();
    }

    public BooleanBinding showBulkApproval() {
        return Bindings.createBooleanBinding(() -> readyVisibleGroups().size() > 1, deps());
    }

    public StringBinding allReadyApprovalLabel() {
        return Bindings.createStringBinding(() -> {
            var ready = readyVisibleGroups();
            var docs = ready.stream().flatMap(g -> g.documentIds().stream()).distinct().count();
            return "Liberar todos os clientes prontos (" + ready.size() + " conjuntos / " + docs + " documentos)";
        }, deps());
    }

    public BooleanBinding canContinueToDispatch() {
        return Bindings.createBooleanBinding(() -> visibleGroups().anyMatch(DocumentDispatchGroup::isApproved), deps());
    }

    public BooleanBinding canSplit() {
        return Bindings.createBooleanBinding(() -> {
            var d = selectedDocument.get();
            if (d == null || d.groupId() == null) {
                return false;
            }
            var g = groups.stream().filter(x -> x.id().equals(d.groupId())).findFirst();
            return g.isPresent() && g.get().documentIds().size() > 1 && splitReason.get().trim().length() >= MIN_REASON;
        }, selectedDocument, groups, splitReason);
    }

    public BooleanBinding hasMergeOptions() {
        return Bindings.isNotEmpty(mergeOptions);
    }

    public BooleanBinding canMerge() {
        return Bindings.createBooleanBinding(() -> selectedGroup.get() != null && selectedMergeOption.get() != null
                && mergeReason.get().trim().length() >= MIN_REASON, selectedGroup, selectedMergeOption, mergeReason);
    }

    public List<OperationalPeriod.YearOption> correctionYears() {
        return shell.years().stream().filter(y -> y.year() != null).toList();
    }

    public List<OperationalPeriod.MonthOption> correctionMonths() {
        return shell.months().stream().filter(m -> m.month() != null && m.month() >= 1 && m.month() <= 12).toList();
    }

    // ================================================================ carregamento

    public void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        tasks.run(() -> review.load(CancellationToken.NONE), this::applyWorkspace, this::fail);
    }

    private void applyWorkspace(DocumentReviewWorkspace workspace) {
        var selectedId = selectedDocument.get() == null ? null : selectedDocument.get().id();
        var mergeId = selectedMergeOption.get() == null ? null : selectedMergeOption.get().groupId();
        documents.setAll(workspace.documents().stream()
                .sorted(Comparator.comparing(ReviewDocument::importedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
        groups.setAll(workspace.groups().stream()
                .sorted(Comparator.comparing(DocumentDispatchGroup::updatedAtUtc, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
        ensureYears(workspace.documents());
        refreshVisible();
        var next = documents.stream().filter(d -> d.id().equals(selectedId)).findFirst()
                .orElse(documents.isEmpty() ? null : documents.getFirst());
        selectedDocument.set(null);
        selectedDocument.set(next);
        mergeOptions.stream().filter(o -> o.groupId().equals(mergeId)).findFirst().ifPresent(selectedMergeOption::set);
        if (!workspace.documents().isEmpty()) {
            importPanelExpanded.set(false);
        }
        reviewWorkspaceSummary.set("Documentos: " + workspace.documents().size() + " • Prontos: " + workspace.eligibleDocumentCount()
                + " • Para corrigir: " + workspace.blockedDocumentCount() + " • Conjuntos liberados: " + approvedVisibleCount());
        refreshCounters();
    }

    /** Anos presentes nos documentos entram no seletor de competência (como {@code EnsureOperationalYears}). */
    private void ensureYears(List<ReviewDocument> docs) {
        var years = new TreeMap<Integer, Boolean>(Comparator.reverseOrder());
        shell.years().stream().filter(y -> y.year() != null).forEach(y -> years.put(y.year(), true));
        var added = false;
        for (var d : docs) {
            var year = effectiveYear(d.period());
            if (year != null && !years.containsKey(year)) {
                years.put(year, true);
                added = true;
            }
        }
        if (added) {
            var selected = shell.selectedYear().get();
            var options = new ArrayList<OperationalPeriod.YearOption>();
            options.add(OperationalPeriod.YearOption.ALL);
            years.keySet().forEach(y -> options.add(new OperationalPeriod.YearOption(y, Integer.toString(y))));
            shell.years().setAll(options);
            shell.selectedYear().set(options.stream().filter(o -> Objects.equals(o.year(), selected == null ? null : selected.year()))
                    .findFirst().orElse(OperationalPeriod.YearOption.ALL));
        }
    }

    private void refreshPeriod() {
        refreshVisible();
        var d = selectedDocument.get();
        if (d != null && !matchesSelectedPeriod(d.period())) {
            selectedDocument.set(visibleDocuments.isEmpty() ? null : visibleDocuments.getFirst());
        }
        refreshCounters();
    }

    private void refreshVisible() {
        visibleDocuments.setAll(documents.stream().filter(d -> matchesSelectedPeriod(d.period())).toList());
    }

    private void refreshCounters() {
        shell.visibleDocumentCount().set(visibleDocuments.size());
        shell.blockedDocumentCount().set((int) visibleDocuments.stream()
                .filter(d -> d.state() == ReviewDocumentState.BLOCKED || d.state() == ReviewDocumentState.DUPLICATE).count());
        shell.approvedGroupCount().set(approvedVisibleCount());
    }

    private int approvedVisibleCount() {
        return (int) visibleGroups().filter(DocumentDispatchGroup::isApproved).count();
    }

    private void onDocumentSelected(ReviewDocument document) {
        removalConfirmation.set(false);
        overrideCandidate.set(null);
        selectedGroup.set(document == null || document.groupId() == null ? null
                : groups.stream().filter(g -> g.id().equals(document.groupId())).findFirst().orElse(null));
        if (document != null) {
            var year = effectiveYear(document.effectivePeriod());
            var month = effectiveMonth(document.effectivePeriod());
            correctionYear.set(correctionYears().stream().filter(y -> Objects.equals(y.year(), year)).findFirst().orElse(null));
            correctionMonth.set(correctionMonths().stream().filter(m -> Objects.equals(m.month(), month)).findFirst().orElse(null));
        }
        refreshGroupDerived();
    }

    private void refreshGroupDerived() {
        var g = selectedGroup.get();
        if (g == null) {
            selectedGroupDocuments.clear();
            mergeOptions.clear();
            selectedMergeOption.set(null);
            return;
        }
        selectedGroupDocuments.setAll(g.documentIds().stream()
                .map(id -> documents.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((ReviewDocument d) -> DocumentPresentation.label(d.documentType()), COLLATOR)
                        .thenComparing(ReviewDocument::fileName, COLLATOR))
                .toList());
        var compatible = groups.stream()
                .filter(x -> !x.id().equals(g.id()) && Objects.equals(x.clientId(), g.clientId())
                        && Objects.equals(x.establishmentId(), g.establishmentId()) && Objects.equals(x.periodKey(), g.periodKey())
                        && Objects.equals(x.groupingPolicyCode(), g.groupingPolicyCode())
                        && Objects.equals(x.groupingPolicyVersion(), g.groupingPolicyVersion()))
                .sorted(Comparator.comparing(DocumentDispatchGroup::createdAtUtc, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        var options = new ArrayList<MergeOption>();
        for (var i = 0; i < compatible.size(); i++) {
            var x = compatible.get(i);
            options.add(new MergeOption(x.id(), "Conjunto compatível " + (i + 1),
                    x.periodLabel() + " • " + x.documentIds().size() + " documento(s) • " + describeTypes(x)));
        }
        mergeOptions.setAll(options);
        selectedMergeOption.set(options.isEmpty() ? null : options.getFirst());
    }

    private String describeTypes(DocumentDispatchGroup group) {
        var types = group.documentIds().stream()
                .map(id -> documents.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null))
                .filter(Objects::nonNull).map(d -> DocumentPresentation.label(d.documentType()))
                .distinct().sorted(COLLATOR).toList();
        return types.isEmpty() ? "tipos não disponíveis" : String.join(", ", types);
    }

    // ================================================================ importação

    public void importFiles(List<Path> files) {
        var distinct = new ArrayList<>(new LinkedHashSet<>(files.stream().map(p -> p.toAbsolutePath().normalize()).toList()));
        if (distinct.isEmpty()) {
            return;
        }
        if (recognizing.get()) {
            shell.status("Já existe uma importação em andamento.");
            return;
        }
        var token = CancellationToken.create();
        importCancellation = token;
        recognizing.set(true);
        importProgress.set(0);
        importTotal.set(distinct.size());
        var existingHashes = documents.stream().map(ReviewDocument::sha256).map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        var existingPaths = documents.stream().map(d -> normalize(d.localPath())).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        var fallbackYear = shell.selectedYear().get() == null ? null : shell.selectedYear().get().year();
        var fallbackMonth = shell.selectedMonth().get() == null ? null : shell.selectedMonth().get().month();
        tasks.run(() -> runImport(distinct, token, existingHashes, existingPaths, fallbackYear, fallbackMonth),
                this::finishImport, error -> {
                    recognizing.set(false);
                    importCancellation = null;
                    fail(error);
                });
    }

    private ImportOutcome runImport(List<Path> files, CancellationToken token, java.util.Set<String> existingHashes,
            java.util.Set<String> existingPaths, Integer fallbackYear, Integer fallbackMonth) {
        var imports = new ArrayList<DocumentReviewService.Import>();
        var staged = new ArrayList<Staged>();
        var failures = new ArrayList<String>();
        var hashes = new ArrayList<String>();
        var periods = new HashSet<String>();
        var already = 0;
        var cancelled = false;
        for (var file : files) {
            try {
                if (token.isCancellationRequested()) {
                    cancelled = true;
                    break;
                }
                var result = recognition.recognize(file, token);
                var sha = result.sha256().toLowerCase(Locale.ROOT);
                if (existingHashes.contains(sha)) {
                    already++;
                    continue;
                }
                var period = periodParser.parse(result.fields());
                var stagedDocument = stage(file, period, fallbackYear, fallbackMonth);
                var normalized = normalize(stagedDocument.path().toString());
                if (normalized != null && existingPaths.contains(normalized)) {
                    already++;
                    continue;
                }
                staged.add(stagedDocument);
                imports.add(new DocumentReviewService.Import(stagedDocument.path().toString(), result));
                existingHashes.add(sha);
                hashes.add(sha);
                if (normalized != null) {
                    existingPaths.add(normalized);
                }
                var y = effectiveYear(period);
                var m = effectiveMonth(period);
                if (y != null && m != null) {
                    periods.add(y + "-" + m);
                }
            } catch (OperationCancelledException e) {
                cancelled = true;
                break;
            } catch (DocumentImportException e) {
                failures.add(file.getFileName() + ": " + e.getMessage());
            } catch (UncheckedIOException e) {
                failures.add(file.getFileName() + ": não foi possível organizar o arquivo na pasta do acervo");
            } catch (RuntimeException e) {
                failures.add(file.getFileName() + ": a importação não pôde ser concluída e nenhuma cópia parcial foi mantida");
            } finally {
                tasks.ui(() -> importProgress.set(importProgress.get() + 1));
            }
        }
        DocumentReviewWorkspace workspace = null;
        if (!imports.isEmpty()) {
            try {
                // 6.20: grava e revalida todos numa única operação.
                workspace = review.importDocuments(imports, CancellationToken.NONE);
            } catch (RuntimeException e) {
                for (var s : staged) {
                    rollback(s, e);
                }
                throw e;
            }
        }
        return new ImportOutcome(workspace, imports.size(), already, failures, hashes, cancelled, periods.size());
    }

    private void finishImport(ImportOutcome outcome) {
        recognizing.set(false);
        importCancellation = null;
        if (outcome.workspace() != null) {
            applyWorkspace(outcome.workspace());
            documents.stream().filter(d -> outcome.importedHashes().contains(d.sha256().toLowerCase(Locale.ROOT))).findFirst()
                    .ifPresent(selectedDocument::set);
        }
        var ignored = outcome.alreadyImported() == 0 ? ""
                : " • " + outcome.alreadyImported() + " já estava(m) importado(s) e não foi(ram) duplicado(s)";
        var period = shell.workPeriodLabel().get();
        String summary;
        if (outcome.cancelled()) {
            summary = "Importação interrompida com segurança após " + outcome.imported()
                    + " documento(s). O que já foi concluído permanece disponível.";
        } else if (outcome.failures().isEmpty()) {
            summary = outcome.distinctPeriods() > 1
                    ? outcome.imported() + " documento(s) importado(s) em " + outcome.distinctPeriods() + " competências" + ignored
                            + ". A competência global foi mantida em " + period + "; use o seletor acima para conferir cada mês."
                    : outcome.imported() + " documento(s) importado(s), reconhecido(s) e organizado(s)" + ignored
                            + ". A competência global continua em " + period + ".";
        } else {
            summary = outcome.imported() + " reconhecido(s)" + ignored + "; " + outcome.failures().size() + " rejeitado(s): "
                    + String.join(" | ", outcome.failures());
        }
        documentImportSummary.set(summary);
        shell.status(summary);
    }

    public void cancelImport() {
        var token = importCancellation;
        if (token != null) {
            token.cancel();
        }
        shell.status("Interrompendo após o documento atual…");
    }

    /** Importa a pasta de entrada; outros formatos são preservados e apenas resumidos. */
    public void importInputFolder() {
        var folder = inputFolder.get().isBlank() ? null : Path.of(inputFolder.get());
        if (folder == null || !Files.isDirectory(folder)) {
            shell.status("Escolha uma pasta de entrada válida antes de importar.");
            return;
        }
        List<Path> all;
        try (Stream<Path> walk = includeSubfolders.get() ? Files.walk(folder) : Files.list(folder)) {
            all = walk.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT))).toList();
        } catch (IOException | UncheckedIOException e) {
            shell.status("Não foi possível ler a pasta de entrada. Confira a permissão da pasta e tente novamente.");
            return;
        }
        var pdfs = all.stream().filter(DocumentsModel::isPdf).toList();
        var unsupported = all.stream().filter(p -> !isPdf(p)).toList();
        if (pdfs.isEmpty()) {
            documentImportSummary.set(unsupported.isEmpty() ? "Nenhum arquivo foi encontrado na pasta de entrada selecionada."
                    : "Nenhum PDF foi importado. " + unsupportedSummary(unsupported));
            shell.status(documentImportSummary.get());
            return;
        }
        if (!unsupported.isEmpty()) {
            var suffix = " • " + unsupportedSummary(unsupported);
            documentImportSummary.addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends String> obs, String old, String now) {
                    documentImportSummary.removeListener(this);
                    if (!now.endsWith(suffix)) {
                        documentImportSummary.set(now + suffix);
                        shell.status(documentImportSummary.get());
                    }
                }
            });
        }
        importFiles(pdfs);
    }

    static String unsupportedSummary(List<Path> files) {
        var formats = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        for (var f : files) {
            var name = f.getFileName().toString();
            var dot = name.lastIndexOf('.');
            var ext = dot <= 0 || dot == name.length() - 1 ? "SEM EXTENSÃO" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
            formats.merge(ext, 1, Integer::sum);
        }
        var head = files.size() == 1 ? "1 arquivo não compatível foi recusado e preservado na pasta"
                : files.size() + " arquivos não compatíveis foram recusados e preservados na pasta";
        return head + " (" + formats.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; ")) + ").";
    }

    private static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** Copia ao acervo {@code ano/mês}; nome com prefixo do SHA-256. Cópia atômica via temporário. */
    private Staged stage(Path source, DocumentPeriod period, Integer fallbackYear, Integer fallbackMonth) {
        if (archiveDirectory == null) {
            return new Staged(source, false);
        }
        var now = OffsetDateTime.now(br.com.contadoresassociados.folhas.application.common.Clock.BRAZIL);
        var year = Objects.requireNonNullElse(effectiveYear(period), Objects.requireNonNullElse(fallbackYear, now.getYear()));
        var month = effectiveMonth(period);
        if (month == null) {
            month = fallbackMonth != null && fallbackMonth >= 1 && fallbackMonth <= 12 ? fallbackMonth : now.getMonthValue();
        }
        try {
            var directory = archiveDirectory.resolve(Integer.toString(year)).resolve("%02d".formatted(month));
            Files.createDirectories(directory);
            var hash = sha256(source);
            var base = source.getFileName().toString().replaceFirst("(?i)\\.pdf$", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
            if (base.isEmpty()) {
                base = "documento";
            }
            var target = directory.resolve(base + "-" + hash.substring(0, 12) + ".pdf");
            if (Files.exists(target)) {
                return new Staged(target, false);
            }
            var temp = Files.createTempFile(directory, ".import-", ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return new Staged(target, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rollback(Staged staged, RuntimeException cause) {
        if (!staged.created()) {
            return;
        }
        try {
            Files.deleteIfExists(staged.path());
        } catch (IOException cleanup) {
            cause.addSuppressed(new DocumentImportException("document.archive_rollback_failed",
                    "A importação falhou e a cópia criada no acervo não pôde ser removida. Não tente reenviar; peça ao suporte para conferir a pasta.",
                    cleanup));
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            var digest = (DigestInputStream) in;
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String path) {
        try {
            return path == null || path.isBlank() ? null : Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================ correções

    public void revalidate() {
        tasks.run(() -> review.revalidate(CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            var blocked = shell.blockedDocumentCount().get();
            shell.status(blocked == 0 ? "Análise atualizada: não há documentos bloqueados nesta competência."
                    : "Análise atualizada: " + blocked + " documento(s) ainda precisam de correção. Selecione o primeiro para ver a causa e a ação recomendada.");
        }, this::fail);
    }

    public void correctPeriod() {
        var d = selectedDocument.get();
        if (d == null) {
            shell.status("Selecione um documento antes de corrigir a competência.");
            return;
        }
        var year = correctionYear.get() == null ? null : correctionYear.get().year();
        var month = correctionMonth.get() == null ? null : correctionMonth.get().month();
        if (year == null || month == null || month < 1 || month > 12) {
            shell.status("Escolha um mês e um ano válidos para a competência.");
            return;
        }
        if (correctionReason.get().trim().length() < MIN_REASON) {
            shell.status("Explique a correção da competência com pelo menos 10 caracteres.");
            return;
        }
        var corrected = new DocumentPeriod(DocumentPeriodKind.MONTHLY, month, year, null, null,
                d.period() == null ? null : d.period().dueDate(), "Correção manual: %02d/%04d".formatted(month, year));
        var reason = correctionReason.get();
        tasks.run(() -> review.correctPeriod(d.id(), corrected, reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            correctionReason.set("");
            shell.status("Competência corrigida para %02d/%04d, reagrupada e registrada no histórico.".formatted(month, year));
        }, this::fail);
    }

    public void restorePeriod() {
        var d = selectedDocument.get();
        if (d == null || d.periodOverride() == null) {
            shell.status("Este documento já usa a competência reconhecida no PDF.");
            return;
        }
        if (correctionReason.get().trim().length() < MIN_REASON) {
            shell.status("Explique por que deseja restaurar a competência reconhecida, com pelo menos 10 caracteres.");
            return;
        }
        var reason = correctionReason.get();
        tasks.run(() -> review.restoreExtractedPeriod(d.id(), reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            correctionReason.set("");
            shell.status("A competência reconhecida no PDF foi restaurada e o documento foi analisado novamente.");
        }, this::fail);
    }

    public void beginRemove() {
        if (selectedDocument.get() == null) {
            shell.status("Selecione o documento que deseja retirar da revisão.");
            return;
        }
        removalConfirmation.set(true);
        shell.status("Confirme abaixo. O registro sairá da revisão, mas o PDF permanecerá no acervo para recuperação.");
    }

    public void prepareRetestRemoval() {
        if (selectedDocument.get() == null) {
            shell.status("Selecione o documento que deseja retirar antes de repetir o teste.");
            return;
        }
        removalReason.set("Retirado para repetir o teste deste período.");
        removalConfirmation.set(true);
        shell.status("Confirme a retirada abaixo. Depois você poderá importar o mesmo PDF novamente; a cópia do acervo continuará preservada.");
    }

    public void cancelRemove() {
        removalConfirmation.set(false);
    }

    public void confirmRemove() {
        var d = selectedDocument.get();
        if (d == null) {
            shell.status("Selecione o documento que deseja retirar da revisão.");
            return;
        }
        if (removalReason.get().trim().length() < MIN_REASON) {
            shell.status("Informe o motivo da retirada com pelo menos 10 caracteres.");
            return;
        }
        var reason = removalReason.get();
        tasks.run(() -> review.removeDocument(d.id(), reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            removalConfirmation.set(false);
            removalReason.set("");
            shell.status(Files.exists(Path.of(d.localPath()))
                    ? "Documento retirado da revisão. O PDF foi preservado no acervo e a retirada ficou registrada no histórico."
                    : "Documento retirado da revisão e registrado no histórico. A cópia indicada no acervo já não estava disponível.");
        }, this::fail);
    }

    public void overrideClient() {
        var d = selectedDocument.get();
        var candidate = overrideCandidate.get();
        if (d == null || candidate == null) {
            shell.status("Selecione um documento e uma alternativa autenticada de cliente.");
            return;
        }
        if (overrideReason.get().trim().length() < MIN_REASON) {
            shell.status("Explique a associação do cliente com pelo menos 10 caracteres.");
            return;
        }
        var reason = overrideReason.get();
        tasks.run(() -> review.overrideClient(d.id(), candidate, reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            overrideReason.set("");
            var updated = documents.stream().filter(x -> x.id().equals(d.id())).findFirst().orElse(null);
            shell.status(updated != null && updated.blockingFindingCount() > 0
                    ? "Cliente associado, mas ainda restam " + updated.blockingFindingCount() + " pendência(s). Veja a próxima ação recomendada."
                    : "Cliente associado, análise refeita e liberação anterior revogada. O documento já pode seguir para o conjunto.");
        }, this::fail);
    }

    /** "Abrir e reativar cadastro": leva ao cadastro do cliente associado ou da 1ª alternativa. */
    public void openInactiveClient() {
        var d = selectedDocument.get();
        if (d == null) {
            shell.status("Selecione um documento com pendência de cliente.");
            return;
        }
        var clientId = d.clientId() != null ? d.clientId()
                : overrideCandidate.get() != null ? overrideCandidate.get().clientId()
                : d.clientAlternatives().isEmpty() ? null : d.clientAlternatives().getFirst().clientId();
        if (clientId == null) {
            shell.status("O documento não contém uma alternativa de cliente autenticada. Cadastre o cliente ou corrija os identificadores do cadastro existente.");
            return;
        }
        openClient.accept(clientId);
    }

    // ================================================================ liberação e organização

    public void approveSelectedGroup() {
        var g = selectedGroup.get();
        if (g == null || !canApproveSelectedGroup().get()) {
            shell.status("Selecione um conjunto pronto para revisão.");
            return;
        }
        tasks.run(() -> review.approveGroup(g.id(), CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            shell.status("Conjunto liberado para preparar a mensagem. Nenhum e-mail foi enviado.");
        }, this::fail);
    }

    public void approveSelectedClientGroups() {
        var ready = readyGroupsForSelectedClient();
        var clientId = ready.isEmpty() ? null : ready.getFirst().clientId();
        if (clientId == null) {
            shell.status(selectedDocument.get() == null ? "Selecione um documento com cliente identificado."
                    : "Este cliente não possui conjuntos prontos para liberação.");
            return;
        }
        var ids = ready.stream().map(DocumentDispatchGroup::id).toList();
        var docs = ready.stream().flatMap(g -> g.documentIds().stream()).distinct().count();
        var periods = ready.stream().map(g -> g.periodLabel().toLowerCase(Locale.ROOT)).distinct().count();
        tasks.run(() -> review.approveClientGroups(clientId, ids, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            shell.status("Cliente liberado em " + ids.size() + " conjunto(s), " + docs + " documento(s) e " + periods
                    + " competência(s). Cada conjunto continua separado para gerar sua própria mensagem; pendências e repetidos ficaram de fora.");
        }, this::fail);
    }

    public void approveAllEligible() {
        if (!singleMonthSelected()) {
            shell.status("Para liberar vários conjuntos, selecione um único mês e ano. Em “Todos os períodos”, libere cada conjunto separadamente.");
            return;
        }
        var ids = readyVisibleGroups().stream().map(DocumentDispatchGroup::id).toList();
        var label = shell.workPeriodLabel().get();
        if (ids.isEmpty()) {
            shell.status("Não há conjuntos prontos para liberação em " + label + ".");
            return;
        }
        tasks.run(() -> review.approveGroups(ids, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            shell.status(ids.size() + " conjunto(s) de " + label + " liberado(s); pendências, repetidos e outros períodos ficaram de fora.");
        }, this::fail);
    }

    public void continueToDispatch() {
        continueToDispatch.run();
        shell.status("Envios usa somente conjuntos liberados em Documentos para " + shell.workPeriodLabel().get() + ".");
    }

    public void splitSelectedDocument() {
        var d = selectedDocument.get();
        if (d == null || d.groupId() == null) {
            shell.status("Selecione um documento pertencente a um conjunto.");
            return;
        }
        var reason = splitReason.get();
        tasks.run(() -> review.splitGroup(d.groupId(), List.of(d.id()), reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            splitReason.set("");
            shell.status("Documento separado. Confira o novo conjunto antes de liberá-lo novamente.");
        }, this::fail);
    }

    public void mergeSelectedGroups() {
        var g = selectedGroup.get();
        var option = selectedMergeOption.get();
        if (g == null || option == null || !canMerge().get()) {
            shell.status("Escolha um conjunto compatível e informe um motivo com pelo menos 10 caracteres.");
            return;
        }
        var reason = mergeReason.get();
        tasks.run(() -> review.mergeGroups(g.id(), option.groupId(), reason, CancellationToken.NONE), ws -> {
            applyWorkspace(ws);
            mergeReason.set("");
            shell.status("Conjuntos unidos. Confira o resultado antes de liberá-lo novamente.");
        }, this::fail);
    }

    public void showAllPeriods() {
        shell.selectedYear().set(OperationalPeriod.YearOption.ALL);
        shell.selectedMonth().set(shell.months().getFirst());
        shell.status("Exibindo documentos, conjuntos e mensagens de todos os períodos.");
    }

    /** Chamado após alterações cadastrais: a revisão já foi revalidada na mesma transação. */
    public void reload() {
        tasks.run(() -> review.load(CancellationToken.NONE), this::applyWorkspace, this::fail);
    }

    // ================================================================ filtro de competência

    boolean singleMonthSelected() {
        var y = shell.selectedYear().get();
        var m = shell.selectedMonth().get();
        return y != null && y.year() != null && m != null && m.month() != null && m.month() > 0;
    }

    private Stream<DocumentDispatchGroup> visibleGroups() {
        return groups.stream().filter(this::matchesSelectedPeriod);
    }

    /** Conjuntos liberados da competência selecionada ({@code VisibleApprovedReviewGroups}). */
    List<DocumentDispatchGroup> visibleApprovedGroups() {
        return visibleGroups().filter(DocumentDispatchGroup::isApproved).toList();
    }

    boolean isVisibleApproved(DocumentDispatchGroup group) {
        return group != null && group.isApproved() && matchesSelectedPeriod(group);
    }

    boolean matchesSelectedPeriod(DocumentPeriod period) {
        var year = effectiveYear(period);
        var month = effectiveMonth(period);
        var selectedYear = shell.selectedYear().get() == null ? null : shell.selectedYear().get().year();
        if (selectedYear != null && !selectedYear.equals(year)) {
            return false;
        }
        var selectedMonth = shell.selectedMonth().get() == null ? null : shell.selectedMonth().get().month();
        if (selectedMonth == null) {
            return true;
        }
        return selectedMonth == 0 ? month == null : selectedMonth.equals(month);
    }

    private boolean matchesSelectedPeriod(DocumentDispatchGroup group) {
        return group.documentIds().stream().anyMatch(id -> documents.stream()
                .anyMatch(d -> d.id().equals(id) && matchesSelectedPeriod(d.period())))
                || matchesPeriodKey(group.periodKey());
    }

    private boolean matchesPeriodKey(String key) {
        int year = 0;
        int month = 0;
        var numbers = key == null ? new String[0] : key.split("[/\\-:_ ]+");
        var values = new ArrayList<Integer>();
        for (var n : numbers) {
            if (n.matches("\\d+")) {
                values.add(Integer.parseInt(n));
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
        if (has) {
            year = values.get(yearIndex);
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

    static Integer effectiveYear(DocumentPeriod p) {
        return p == null ? null : p.year() != null ? p.year() : p.startDate() != null ? p.startDate().getYear() : null;
    }

    static Integer effectiveMonth(DocumentPeriod p) {
        return p == null ? null : p.month() != null ? p.month() : p.startDate() != null ? p.startDate().getMonthValue() : null;
    }

    private void fail(Throwable error) {
        shell.status(switch (error) {
            case DocumentReviewException e -> "Não foi possível concluir a revisão: " + e.getMessage();
            case DocumentImportException e -> e.getMessage();
            default -> ErrorMessages.friendly(error);
        });
    }
}
