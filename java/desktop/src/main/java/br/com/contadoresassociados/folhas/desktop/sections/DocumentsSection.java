package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionCandidate;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationFinding;
import br.com.contadoresassociados.folhas.desktop.state.DocumentsModel;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/** Fluxo de documentos — adicionar, conferir, corrigir e liberar (mesma ordem de {@code MainWindow.axaml}). */
public final class DocumentsSection implements Section {

    private final DocumentsModel model;
    private final ShellState shell;
    private final Node root;
    private final ObservableList<ValidationFinding> findings = FXCollections.observableArrayList();
    private final ObservableList<RecognizedField> fields = FXCollections.observableArrayList();
    private final ObservableList<ClientResolutionCandidate> alternatives = FXCollections.observableArrayList();

    public DocumentsSection(DocumentsModel model, ShellState shell) {
        this.model = model;
        this.shell = shell;
        model.selectedDocument.addListener((obs, old, now) -> {
            findings.setAll(now == null ? List.of() : now.findings());
            fields.setAll(now == null ? List.of() : now.fields());
            alternatives.setAll(now == null ? List.of() : now.clientAlternatives());
        });
        var selection = model.hasSelection();
        root = scroll(new VBox(14, steps(), addDocuments(), reviewList(),
                visibleWhen(selectedCard(), selection),
                visibleWhen(corrections(), selection),
                visibleWhen(approval(), selection),
                visibleWhen(manualOrganization(), model.hasVisibleGroups()),
                visibleWhen(recognitionDetails(), selection)), 20);
        root.setAccessibleText("Fluxo de documentos");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShown() {
        model.loadIfNeeded();
    }

    // ------------------------------------------------------------------ etapas

    private Node steps() {
        var grid = columns(12, 0, 1, 1, 1);
        grid.add(step("1", "Adicionar", "Arquivos ou pasta", model.isImportStepCurrent()), 0, 0);
        grid.add(step("2", "Conferir", "Um documento por vez", model.isReviewStepCurrent()), 1, 0);
        grid.add(step("3", "Liberar", "Para preparar a mensagem", model.isApprovalStepCurrent()), 2, 0);
        var card = card(0, grid);
        card.setStyle("-fx-padding: 14;");
        return card;
    }

    private static Node step(String number, String title, String subtitle, BooleanBinding current) {
        var circle = new StackPane(text(number, "strong"));
        circle.getStyleClass().add("step-circle");
        Runnable refresh = () -> {
            circle.getStyleClass().remove("current");
            if (current.get()) {
                circle.getStyleClass().add("current");
            }
        };
        current.addListener((obs, old, now) -> refresh.run());
        refresh.run();
        var box = new HBox(9, circle, new VBox(text(title, "strong"), text(subtitle, "muted")));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ------------------------------------------------------------------ 1 · adicionar

    private Node addDocuments() {
        var choose = button("Escolher PDFs", "primary", this::choosePdfs);
        var head = new HBox(12, grow(heading("1 · ADICIONAR DOCUMENTOS", "Arraste PDFs aqui ou escolha os arquivos",
                "O aplicativo lê PDFs e organiza o acervo por competência. Limite: 25 MB e 100 páginas por documento.")), choose);
        head.setAlignment(Pos.CENTER_LEFT);

        var folderLabel = text(model.inputFolderDisplay(), "row-sub");
        folderLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        var folderRow = new HBox(10, grow(new HBox(folderLabel)), button("Escolher e importar pasta", "secondary", this::chooseFolder));
        folderRow.setAlignment(Pos.CENTER_LEFT);
        var again = visibleWhen(button("Importar novamente a pasta escolhida", "ghost", model::importInputFolder), model.hasInputFolder());
        var subRow = new HBox(10, grow(new HBox(checkBox("Incluir também as subpastas", model.includeSubfolders))), again);
        subRow.setAlignment(Pos.CENTER_LEFT);
        var folder = expander("Usar uma pasta inteira", new VBox(10,
                muted("Escolha a pasta e o aplicativo importará os PDFs encontrados nela. Outros formatos permanecem no lugar e aparecem apenas no resumo."),
                folderRow, subRow), true);
        folder.expandedProperty().bindBidirectional(model.importPanelExpanded);

        var progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setPrefHeight(6);
        progress.progressProperty().bind(Bindings.createDoubleBinding(() -> model.importTotal.get() == 0 ? 0
                : (double) model.importProgress.get() / model.importTotal.get(), model.importProgress, model.importTotal));
        progress.setAccessibleText("Progresso da importação");
        var progressRow = new HBox(10, grow(new VBox(4, colored(text(model.importProgressText()), "#6B604C", false), progress)),
                button("Interromper", "ghost", model::cancelImport));
        progressRow.setAlignment(Pos.CENTER_LEFT);

        var hidden = new HBox(10, grow(new HBox(wrap(colored(text(model.hiddenDocumentsText()), "#5E4A1E", false)))),
                button("Mostrar todos", "ghost", model::showAllPeriods));
        hidden.setAlignment(Pos.CENTER_LEFT);
        boxed(hidden, "#FFF8E8", "#E4C56C", 10, "8 11 8 11");

        var card = card(12, head, folder, muted(model.documentImportSummary),
                visibleWhen(progressRow, model.recognizing), visibleWhen(hidden, model.hasHiddenDocuments()));
        enableDrop(card);
        return card;
    }

    private void enableDrop(Node target) {
        target.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragDropped(event -> {
            var files = event.getDragboard().hasFiles() ? event.getDragboard().getFiles() : List.<File>of();
            model.importFiles(files.stream().map(File::toPath).filter(p -> p.toString().toLowerCase().endsWith(".pdf")).toList());
            event.setDropCompleted(!files.isEmpty());
            event.consume();
        });
    }

    private void choosePdfs() {
        var chooser = new FileChooser();
        chooser.setTitle("Escolha os PDFs para importar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Documentos PDF", "*.pdf", "*.PDF"));
        var files = chooser.showOpenMultipleDialog(root.getScene().getWindow());
        if (files != null) {
            model.importFiles(files.stream().map(File::toPath).toList());
        }
    }

    private void chooseFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Escolha a pasta de entrada");
        if (!model.inputFolder.get().isBlank() && new File(model.inputFolder.get()).isDirectory()) {
            chooser.setInitialDirectory(new File(model.inputFolder.get()));
        }
        var folder = chooser.showDialog(root.getScene().getWindow());
        if (folder != null) {
            model.inputFolder.set(Path.of(folder.getAbsolutePath()).toString());
            model.importInputFolder();
        }
    }

    // ------------------------------------------------------------------ 2 · conferir

    private Node reviewList() {
        var list = listView(model.visibleDocuments, model.selectedDocument, this::documentRow, 104);
        list.setMinHeight(110);
        list.setMaxHeight(330);
        list.setAccessibleText("Documentos da competência");
        var empty = new StackPane(wrap(colored(new Label("Nenhum documento deste período. Use “Mostrar todos” acima."), "#6B604C", false)));
        boxed(empty, "#F8F5EE", null, 9, "12");
        var more = expander("Mais opções para estes arquivos", new VBox(8,
                muted("Use apenas se os cadastros ou as regras de leitura mudaram depois da importação."),
                button("Analisar novamente os documentos deste período", "ghost", model::revalidate)), false);
        return card(10, new VBox(3, eyebrow("2 · CONFERIR DOCUMENTOS"), h2("Escolha um documento para ver a próxima ação"),
                        muted(model.reviewWorkspaceSummary), text(shell.workPeriodLabel(), "period-accent")),
                list, visibleWhen(empty, model.showEmptyState()), more);
    }

    private Node documentRow(ReviewDocument d) {
        var name = text(d.fileName(), "row-title");
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        var meta = new HBox(8, text(DocumentPresentation.label(d.documentType()), "row-accent"),
                text(d.period() == null ? "" : d.period().displayLabel(), "row-sub-small"));
        meta.setAlignment(Pos.CENTER_LEFT);
        var client = colored(new Label(d.clientDisplayName() == null ? "Cliente ainda não identificado" : d.clientDisplayName()),
                "#4F483E", true);
        var left = new VBox(4, name, meta, client, text(d.clientTaxIdMasked() == null ? "" : d.clientTaxIdMasked(), "row-sub-small"));
        HBox.setHgrow(left, Priority.ALWAYS);
        var summary = wrap(colored(new Label(d.reviewSummary()), "#5E594F", false));
        summary.setStyle(summary.getStyle() + " -fx-font-size: 11px;");
        summary.setMaxWidth(210);
        var row = new HBox(12, left, summary);
        row.setAlignment(Pos.CENTER_LEFT);
        return rowBox(row);
    }

    // ------------------------------------------------------------------ documento selecionado

    private Node selectedCard() {
        var title = new VBox(3, eyebrow("DOCUMENTO SELECIONADO"), h2(model.selectedFileName()),
                colored(text(model.selectedTypeLabel()), "#8C691B", false));
        var state = new VBox(colored(text(model.selectedStateLabel()), "#5E594F", true),
                colored(text(model.selectedPendingLabel()), "#8E5549", false));
        boxed(state, "#F1EDE4", null, 15, "8 12 8 12");
        var head = new HBox(10, grow(title), state);
        head.setAlignment(Pos.TOP_LEFT);

        var summary = new VBox(4, wrap(colored(text(model.clientSummary()), "#3F5F3D", true)),
                colored(text(model.recognitionMethodSummary()), "#6E6254", false),
                wrap(colored(text(model.groupingSummary()), "#5D574E", false)));
        boxed(summary, "#F3EFE6", null, 10, "12");

        var groupDocs = itemsControl(model.selectedGroupDocuments, d -> {
            var left = new VBox(3, text(d.fileName(), "row-title"), new HBox(10,
                    text(DocumentPresentation.label(d.documentType()), "row-accent"),
                    text(d.period() == null ? "" : d.period().displayLabel(), "row-sub")));
            HBox.setHgrow(left, Priority.ALWAYS);
            var stateLabel = colored(new Label(FriendlyText.of(d.state())), "#5E594F", false);
            return softBox(new HBox(12, left, stateLabel));
        }, 5);
        var viewer = expander(model.groupViewerHeader(), new VBox(7,
                muted("Estes arquivos pertencem ao mesmo cliente, unidade, competência e regra de mensagem. Conferir o conjunto não altera nenhuma aprovação."),
                groupDocs), false);

        var guidance = new VBox(3, colored(new Label("Próxima ação"), "#8B6514", true), wrap(colored(text(model.guidance()), "#4F483E", false)));
        boxed(guidance, "#F7F1E2", null, 9, "12");

        var findingList = itemsControl(findings, f -> {
            var severity = colored(new Label(FriendlyText.of(f.severity())), "#96523D", true);
            severity.setMinWidth(150);
            severity.setPrefWidth(150);
            var message = wrap(colored(new Label(f.message()), "#4F483E", false));
            HBox.setHgrow(message, Priority.ALWAYS);
            return boxed(new HBox(10, severity, message), "#FFF6ED", "#E8D3BF", 8, "11");
        }, 6);

        return card(11, head, summary, visibleWhen(viewer, model.hasGroupDocuments()), guidance, findingList);
    }

    // ------------------------------------------------------------------ correções

    private Node corrections() {
        var inactive = new HBox(10, grow(new VBox(3, colored(new Label("Cadastro associado está inativo"), "#6C4D11", true),
                muted("Abra o cadastro, reative-o e volte a Documentos. A conferência será refeita automaticamente."))),
                button("Abrir e reativar cadastro", "primary", model::openInactiveClient));
        inactive.setAlignment(Pos.CENTER_LEFT);
        boxed(inactive, "#FFF5E4", "#E5C36B", 9, "12");

        var candidate = new ComboBox<>(alternatives);
        candidate.valueProperty().bindBidirectional(model.overrideCandidate);
        candidate.setPromptText("Escolha o cliente correto");
        candidate.setMaxWidth(Double.MAX_VALUE);
        candidate.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ClientResolutionCandidate c) {
                return c == null ? "" : c.displayName();
            }

            @Override
            public ClientResolutionCandidate fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        var overrideRow = new HBox(8, grow(textField(model.overrideReason, "Motivo da confirmação (mínimo 10 caracteres)")),
                button("Confirmar cliente", "secondary", model::overrideClient));
        var override = new VBox(7, fieldLabel("Confirmar o cliente correto"),
                muted("Escolha somente quando o PDF corresponde a uma das alternativas abaixo."), candidate, overrideRow);

        var year = new ComboBox<>(FXCollections.observableArrayList(model.correctionYears()));
        shell.years().addListener((javafx.collections.ListChangeListener<Object>) c -> year.getItems().setAll(model.correctionYears()));
        year.valueProperty().bindBidirectional(model.correctionYear);
        year.setMaxWidth(Double.MAX_VALUE);
        year.setAccessibleText("Ano correto");
        var month = new ComboBox<>(FXCollections.observableArrayList(model.correctionMonths()));
        month.valueProperty().bindBidirectional(model.correctionMonth);
        month.setMaxWidth(Double.MAX_VALUE);
        month.setAccessibleText("Mês correto");
        var periodGrid = columns(8, 8, 1, 1);
        periodGrid.add(year, 0, 0);
        periodGrid.add(month, 1, 0);
        periodGrid.add(textField(model.correctionReason, "Motivo da correção (mínimo 10 caracteres)"), 0, 1, 2, 1);
        var restore = button("Voltar à competência reconhecida", "ghost", model::restorePeriod);
        restore.disableProperty().bind(model.canRestorePeriod().not());
        var period = new VBox(7, fieldLabel("Corrigir competência"),
                muted("Use somente quando o mês ou ano reconhecido não corresponde ao documento."), periodGrid,
                right(restore, button("Salvar competência correta", "secondary", model::correctPeriod)));

        var remove = new HBox(10, grow(new VBox(3, fieldLabel("Retirar este arquivo da conferência"),
                muted("O PDF permanece no acervo. Você poderá importá-lo novamente para repetir o teste ou corrigir o período."))),
                new FlowPane(8, 8, button("Retirar deste período", "danger", model::beginRemove),
                        button("Retirar e testar de novo", "ghost", model::prepareRetestRemoval)));
        remove.setAlignment(Pos.CENTER_LEFT);
        var confirm = new VBox(8, colored(new Label("Confirmar retirada recuperável"), "#7A3328", true),
                wrap(colored(new Label("O PDF não será apagado. Informe por que este item saiu da conferência."), "#6A4942", false)),
                textField(model.removalReason, "Ex.: importado na competência errada"),
                right(button("Cancelar", "ghost", model::cancelRemove), button("Confirmar retirada", "secondary", model::confirmRemove)));
        boxed(confirm, "#FFF0ED", "#D99A8C", 9, "12");

        var content = new VBox(14,
                muted("Abra esta área quando o cliente, a competência ou a importação precisarem de ajuste. Toda alteração revoga aprovações anteriores e fica registrada."),
                visibleWhen(inactive, model.hasInactiveClientIssue()),
                visibleWhen(override, model.hasClientAlternatives()),
                divider(), period, divider(), remove, visibleWhen(confirm, model.removalConfirmation));
        var pane = expander("Corrigir ou retirar o documento selecionado", content, false);
        pane.expandedProperty().bindBidirectional(model.correctionPanelExpanded);
        return pane;
    }

    // ------------------------------------------------------------------ 3 · liberar

    private Node approval() {
        var summary = soft(4, wrap(colored(text(model.groupingSummary()), "#4F483E", true)), muted(model.groupStatusSummary()));
        var approveSet = button(model.setApprovalLabel(), "primary", model::approveSelectedGroup);
        approveSet.disableProperty().bind(model.canApproveSelectedGroup().not());
        var approveClient = visibleWhen(button(model.clientApprovalLabel(), "secondary", model::approveSelectedClientGroups),
                model.showClientApproval());
        var approveAll = visibleWhen(button(model.allReadyApprovalLabel(), "ghost", model::approveAllEligible), model.showBulkApproval());
        Button next = button("Continuar com os documentos já liberados", "secondary", model::continueToDispatch);
        next.disableProperty().bind(model.canContinueToDispatch().not());
        var help = new HBox(wrap(colored(text(model.clientApprovalHelp()), "#66532C", false)));
        boxed(help, "#F7F1E2", null, 9, "8 11 8 11");
        return card(11, new VBox(3, eyebrow("3 · LIBERAR PARA A MENSAGEM"), h2("Confirme somente depois de conferir"),
                        muted("Liberar não envia e-mail. Apenas autoriza estes documentos a seguirem para a preparação da mensagem."),
                        muted("Um conjunto reúne documentos do mesmo cliente, unidade e competência que podem seguir na mesma mensagem. Férias, rescisões e exceções permanecem separadas.")),
                summary, new FlowPane(8, 8, approveSet, approveClient, approveAll, next),
                visibleWhen(help, model.showClientApproval()));
    }

    // ------------------------------------------------------------------ organização manual

    private Node manualOrganization() {
        var split = button("Separar este documento", "secondary", model::splitSelectedDocument);
        split.disableProperty().bind(model.canSplit().not());
        var fileName = colored(text(model.selectedFileName()), "#8C691B", true);
        var splitBox = visibleWhen(soft(8, text("Separar somente o documento selecionado", "subtitle"), fileName,
                muted("Use quando este arquivo precisa seguir em uma mensagem própria, sem alterar os demais documentos do conjunto."),
                textField(model.splitReason, "Explique a exceção (mínimo 10 caracteres)"), split), model.hasSelection());

        var options = new ComboBox<>(model.mergeOptions);
        options.valueProperty().bindBidirectional(model.selectedMergeOption);
        options.setPromptText("Escolha o conjunto que deve ser unido");
        options.setMaxWidth(Double.MAX_VALUE);
        var none = new HBox(wrap(colored(new Label("Não há outro conjunto compatível com o documento selecionado. Não é necessário fazer nada."),
                "#716B60", false)));
        boxed(none, "#FFFFFF", null, 8, "10");
        var merge = button("Unir os conjuntos escolhidos", "secondary", model::mergeSelectedGroups);
        merge.disableProperty().bind(model.canMerge().not());
        var has = model.hasMergeOptions();
        var mergeBox = soft(8, text("Unir conjuntos realmente compatíveis", "subtitle"),
                muted("Somente conjuntos do mesmo cliente, unidade, competência e regra contábil aparecem aqui. Os nomes incluem período, quantidade e tipos para evitar escolhas repetidas ou ambíguas."),
                visibleWhen(options, has), hiddenWhen(none, has),
                visibleWhen(textField(model.mergeReason, "Explique por que estes conjuntos devem ser unidos (mínimo 10 caracteres)"), has),
                visibleWhen(merge, has));
        return expander("Organização manual — somente se precisar separar ou unir", new VBox(12,
                muted("O aplicativo já separa automaticamente por cliente, unidade, competência e regra contábil. Abra esta área somente para corrigir uma exceção que você conferiu."),
                splitBox, mergeBox), false);
    }

    // ------------------------------------------------------------------ identificação

    private Node recognitionDetails() {
        var list = itemsControl(fields, f -> {
            var head = new HBox(9, grow(wrap(text(f.name(), "row-title"))),
                    text(f.evidence() == null ? "" : "Página " + f.evidence().pageNumber(), "row-sub-small"));
            return softBox(new VBox(3, head, wrap(colored(new Label(FriendlyText.of(f.role())), "#8C691B", false)),
                    wrap(colored(new Label(f.displayValue() == null ? "" : f.displayValue()), "#5D574E", false))));
        }, 6);
        return expander("Como o aplicativo identificou este documento", new VBox(9,
                muted("Estes detalhes servem para uma conferência excepcional; não precisam ser preenchidos."), list), false);
    }
}
