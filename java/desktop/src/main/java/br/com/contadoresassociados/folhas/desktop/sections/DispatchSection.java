package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.desktop.state.DispatchModel;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/** Mensagens e envios — preparar, conferir, aprovar e concluir (mesma ordem de {@code MainWindow.axaml}). */
public final class DispatchSection implements Section {

    private static final String WARNING_BG = "#FFF4D8";
    private static final String WARNING_FG = "#694B10";
    private static final String SAFE_BG = "#EAF4E8";
    private static final String SAFE_FG = "#2D6535";

    private final DispatchModel model;
    private final Node root;

    public DispatchSection(DispatchModel model) {
        this.model = model;
        var content = new VBox(14, overview(), chooseClients(),
                hiddenWhen(placeholder(), model.hasSelectedItem()),
                visibleWhen(review(), model.hasSelectedItem()),
                visibleWhen(blocks(), model.hasSelectedBlocks()),
                visibleWhen(approval(), model.showApprovalPanel()),
                visibleWhen(completion(), model.showCompletionPanel()),
                connection());
        content.setMaxWidth(1080);
        var centered = new HBox(content);
        centered.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(content, Priority.ALWAYS);
        root = scroll(centered, 28);
        root.setAccessibleText("Mensagens e envios");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShown() {
        model.refresh();
    }

    // ------------------------------------------------------------------ etapas, modo e segurança

    private Node overview() {
        var steps = new FlowPane(22, 8, step("1", "Preparar", model.isPrepareStepCurrent()),
                step("2", "Conferir", model.isReviewStepCurrent()), step("3", "Aprovar", model.isApprovalStepCurrent()),
                step("4", "Concluir", model.isCompletionStepCurrent()));

        var mode = comboBox(model.operationModes, model.operationMode);
        mode.disableProperty().bind(model.canSelectOperationMode().not());
        mode.setAccessibleText("Modo da próxima mensagem");
        var modeBox = new VBox(5, fieldLabel("Modo da próxima mensagem"), mode, muted(model.modeHelp()));
        modeBox.setMaxWidth(390);

        var mark = new Label("!");
        var circle = new StackPane(mark);
        circle.setStyle("-fx-background-color: rgba(255,255,255,0.92); -fx-background-radius: 15;");
        circle.setMinSize(30, 30);
        circle.setMaxSize(30, 30);
        var title = wrap(text(model.safetyTitle()));
        var message = wrap(text(model.safetyMessage()));
        var safety = new HBox(10, circle, grow(new VBox(2, title, message)));
        safety.setAlignment(Pos.CENTER_LEFT);
        safety.setAccessibleText("Situação de segurança do envio");
        Runnable paint = () -> {
            var warning = model.safetyIsWarning().get();
            var fg = warning ? WARNING_FG : SAFE_FG;
            boxed(safety, warning ? WARNING_BG : SAFE_BG, "#D8D2C6", 10, "12");
            mark.setStyle("-fx-text-fill: " + fg + "; -fx-font-weight: bold;");
            title.setStyle("-fx-text-fill: " + fg + "; -fx-font-weight: 600; -fx-font-size: 15px;");
            message.setStyle("-fx-text-fill: " + fg + ";");
        };
        var warning = model.safetyIsWarning();
        warning.addListener((obs, old, now) -> paint.run());
        safety.getProperties().put("binding", warning);
        paint.run();

        var card = card(14, steps, muted(model.workspaceSummary), modeBox, safety);
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private static Node step(String number, String title, BooleanBinding current) {
        var circle = new StackPane(text(number, "strong"));
        circle.getStyleClass().add("step-circle");
        Runnable refresh = () -> {
            circle.getStyleClass().remove("current");
            if (current.get()) {
                circle.getStyleClass().add("current");
            }
        };
        current.addListener((obs, old, now) -> refresh.run());
        circle.getProperties().put("binding", current);
        refresh.run();
        var box = new HBox(7, circle, text(title, "strong"));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ------------------------------------------------------------------ 1 · escolha os clientes

    private Node chooseClients() {
        var groups = listView(model.approvedGroups, model.selectedGroup(), this::groupRow, 78);
        groups.setMinHeight(90);
        groups.setMaxHeight(190);
        groups.setAccessibleText("Documentos liberados para preparar");
        var noGroups = emptyBox(new Label(
                "Ainda não há documentos liberados nesta competência. Volte a Documentos, resolva as pendências e faça a liberação."));

        var prepare = button("Preparar este conjunto", "primary", model::prepareSelected);
        prepare.disableProperty().bind(model.canPrepareSelected().not().or(model.busy));
        var prepareAll = button("Preparar todos os clientes liberados", "secondary", model::prepareApprovedBatch);
        prepareAll.disableProperty().bind(model.canPrepareBatch().not().or(model.busy));
        var back = button("Voltar aos documentos", "ghost", model::backToDocuments);

        var search = textField(model.searchText, "Buscar cliente, período, assunto ou arquivo");
        search.setAccessibleText("Buscar nas mensagens preparadas");
        var filter = comboBox(model.queueFilters, model.queueFilter);
        filter.setAccessibleText("Filtrar mensagens preparadas");
        var tools = columns(10, 0, 2, 1);
        tools.add(search, 0, 0);
        tools.add(filter, 1, 0);

        var queue = listView(model.visibleItems, model.selectedItem, this::itemRow, 86);
        queue.setMaxHeight(210);
        queue.setAccessibleText("Mensagens preparadas");
        var queueEmpty = emptyBox(text(model.queueEmptyMessage()));

        var card = card(12, eyebrow("1 · ESCOLHA OS CLIENTES"), h2("Documentos já liberados para mensagem"),
                muted("Cada conjunto reúne os documentos de um cliente, uma unidade e uma regra contábil. Prepare somente o conjunto escolhido ou todos os conjuntos liberados desta competência."),
                visibleWhen(groups, model.hasApprovedGroups()), hiddenWhen(noGroups, model.hasApprovedGroups()),
                new FlowPane(8, 8, prepare, prepareAll, back), divider(), eyebrow("MENSAGENS PREPARADAS"),
                muted("A lista mantém uma mensagem atual por conjunto. Use a busca ou o filtro para localizar rapidamente um cliente, período, assunto ou arquivo."),
                tools, colored(text(model.queueSummary()), "#6B604C", false),
                hiddenWhen(queue, model.showQueueEmptyState()), visibleWhen(queueEmpty, model.showQueueEmptyState()));
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private static Node emptyBox(Label label) {
        wrap(label).getStyleClass().add("muted");
        label.setTextAlignment(TextAlignment.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        var box = new StackPane(label);
        return boxed(box, "#F8F5EE", "#E5DFD4", 9, "14");
    }

    private Node groupRow(DocumentDispatchGroup g) {
        var name = text(g.clientDisplayName() == null ? "" : g.clientDisplayName(), "row-title");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        var meta = new HBox(10, small("Conjunto " + DispatchModel.shortId(g.id()), "#8C691B", false),
                small(g.periodLabel(), "#766F62", false), small(g.documentIds().size() + " documento(s)", "#766F62", false));
        var summary = wrap(small(g.approvalSummary() == null ? "" : g.approvalSummary(), "#766F62", false));
        var left = new VBox(3, name, meta, summary);
        HBox.setHgrow(left, Priority.ALWAYS);
        var released = colored(new Label("Liberado"), "#2F733B", true);
        var row = new HBox(10, left, released);
        row.setAlignment(Pos.CENTER_LEFT);
        return softBox(row);
    }

    private Node itemRow(DispatchItem i) {
        var head = new HBox(10, small("Mensagem " + DispatchModel.shortId(i.id()), "#8C691B", true),
                small(i.periodLabel(), "#766F62", false));
        var name = text(i.clientDisplayName() == null ? "" : i.clientDisplayName(), "row-title");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        var attachments = i.message() == null ? "Sem anexos preparados" : i.message().attachments().size() + " anexo(s)";
        var meta = new HBox(8, small("Conjunto " + DispatchModel.shortId(i.groupId()), "#6B5120", true),
                small(attachments, "#766F62", false));
        var left = new VBox(3, head, name, meta);
        HBox.setHgrow(left, Priority.ALWAYS);
        var state = small(FriendlyText.of(i.state()), "#655F55", false);
        state.setStyle(state.getStyle() + " -fx-font-size: 10px;");
        var right = new VBox(2, small(FriendlyText.of(i.mode()), "#8C691B", false), state);
        right.setAlignment(Pos.CENTER_RIGHT);
        var row = new HBox(12, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return boxed(new VBox(row), "#F8F5EE", "#E5DFD4", 8, "10");
    }

    private static Label small(String value, String color, boolean bold) {
        var label = colored(new Label(value == null ? "" : value), color, bold);
        label.setStyle(label.getStyle() + " -fx-font-size: 11px;");
        return label;
    }

    // ------------------------------------------------------------------ 2 · confira

    private Node placeholder() {
        var title = h2("Prepare ou escolha uma mensagem");
        title.setTextAlignment(TextAlignment.CENTER);
        var text = muted("A conferência do destinatário, do texto e dos anexos aparecerá aqui, na mesma ordem em que deve ser feita.");
        text.setTextAlignment(TextAlignment.CENTER);
        text.setMaxWidth(660);
        var box = new VBox(7, title, text);
        box.setAlignment(Pos.CENTER);
        var card = card(0, box);
        card.setStyle("-fx-padding: 24;");
        return card;
    }

    private Node review() {
        var title = new VBox(3, eyebrow("2 · CONFIRA DESTINATÁRIO, TEXTO E ANEXOS"),
                colored(text(model.selectedReference()), "#8C691B", true), wrap(h2(model.selectedSubject())),
                muted(model.selectedContextSummary()));
        var another = button("Preparar outra mensagem", "ghost", model::prepareAnother);
        var head = new HBox(12, grow(title), another);
        head.setAlignment(Pos.TOP_LEFT);

        var recipients = soft(5, fieldLabel("Destinatário usado nesta operação"),
                wrap(colored(text(model.selectedRecipients()), "#8C691B", false)));

        var body = new TextArea();
        body.textProperty().bind(model.selectedBody());
        body.setEditable(false);
        body.setWrapText(true);
        body.setMinHeight(180);
        body.setPrefHeight(260);
        body.setMaxHeight(360);
        body.setAccessibleText("Texto da mensagem");

        var attachments = itemsControl(model.selectedAttachments, a -> {
            var name = new Label(a.fileName());
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            var type = colored(new Label(DocumentPresentation.label(a.documentType())), "#766F62", false);
            return softBox(new HBox(10, name, type));
        }, 5);

        var card = card(12, head, recipients, fieldLabel("Texto da mensagem"), body,
                colored(text(model.attachmentsHeader()), "#5D574E", true), attachments);
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private Node blocks() {
        var list = itemsControl(model.selectedBlocks, b -> boxed(new VBox(wrap(colored(new Label(b.message()), "#5B4B42", false))),
                "#FFF6ED", "#E8D3BF", 8, "10"), 5);
        var card = card(9, h2("Corrija antes de prosseguir"), list);
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    // ------------------------------------------------------------------ 3 · aprove

    private Node approval() {
        var approve = button("Aprovar esta mensagem", "primary", model::approveSelected);
        approve.disableProperty().bind(model.canApproveSelected().not().or(model.busy));
        var approveAll = button("Aprovar todas as mensagens prontas", "secondary", model::approveSelectedBatch);
        approveAll.disableProperty().bind(model.canApproveSelectedBatch().not().or(model.busy));
        var card = card(10, eyebrow("3 · APROVE DEPOIS DA CONFERÊNCIA"), h2("Confirmação humana"),
                muted("A aprovação confirma o cliente, o destinatário, o texto e os anexos. Ela ainda não executa a ação."),
                new FlowPane(8, 8, approve, approveAll));
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    // ------------------------------------------------------------------ 4 · conclua

    private Node completion() {
        var send = model.isSendMode();

        var single = button("Enviar somente esta mensagem", "primary", model::executeSelected);
        single.disableProperty().bind(model.canExecuteSelected().not().or(model.busy));
        var singleBox = soft(7, bigLabel("Somente esta mensagem"),
                wrap(colored(text(model.confirmationGuidance()), "#8C691B", true)),
                textField(model.confirmationPhrase, "Confirmação desta mensagem"), single);
        singleBox.setStyle(singleBox.getStyle() + " -fx-padding: 14;");

        var batch = button("Enviar todas as mensagens aprovadas", "secondary", model::executeSelectedBatch);
        batch.disableProperty().bind(model.canExecuteSelectedBatch().not().or(model.busy));
        var batchBox = soft(7, bigLabel("Todas as mensagens aprovadas desta sequência"),
                wrap(colored(text(model.batchConfirmationGuidance()), "#8C691B", true)),
                textField(model.batchConfirmationPhrase, "Confirmação de todas as mensagens"), batch);
        batchBox.setStyle(batchBox.getStyle() + " -fx-padding: 14;");
        var sendArea = new VBox(10, singleBox, visibleWhen(batchBox, model.showBatchConfirmation()));

        var execute = button(model.actionLabel(), "primary", model::executeSelected);
        execute.disableProperty().bind(model.canExecuteSelected().not().or(model.busy));
        var executeAll = button("Concluir todas as mensagens aprovadas", "secondary", model::executeSelectedBatch);
        executeAll.disableProperty().bind(model.canExecuteSelectedBatch().not().or(model.busy));
        var otherArea = new FlowPane(8, 8, execute, executeAll);

        var outcomeTitle = wrap(text(model.outcomeTitle()));
        var outcomeMessage = wrap(text(model.outcomeMessage()));
        var outcome = new VBox(4, outcomeTitle, outcomeMessage);
        outcome.setAccessibleText("Resultado da operação");
        Runnable paint = () -> {
            var warning = model.safetyIsWarning().get();
            var fg = warning ? WARNING_FG : SAFE_FG;
            boxed(outcome, warning ? WARNING_BG : SAFE_BG, "#D8D2C6", 12, "14");
            outcomeTitle.setStyle("-fx-text-fill: " + fg + "; -fx-font-size: 17px; -fx-font-weight: 600;");
            outcomeMessage.setStyle("-fx-text-fill: " + fg + ";");
        };
        var warning = model.safetyIsWarning();
        warning.addListener((obs, old, now) -> paint.run());
        outcome.getProperties().put("binding", warning);
        paint.run();

        var reconcile = button("Conferir situação sem repetir", "ghost", model::reconcileSelected);
        reconcile.disableProperty().bind(model.busy);
        var incident = button("Relatar ocorrência", "ghost", model::reportIncident);
        var recovery = new FlowPane(8, 8, reconcile, incident);

        var card = card(10, eyebrow("4 · CONCLUA A AÇÃO ESCOLHIDA"), h2(model.actionLabel()),
                visibleWhen(sendArea, send), hiddenWhen(otherArea, send),
                visibleWhen(outcome, model.hasOutcome()), visibleWhen(recovery, model.canReconcileSelected()));
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private static Label bigLabel(String value) {
        var label = new Label(value);
        label.setStyle("-fx-font-weight: 600; -fx-font-size: 16px;");
        return label;
    }

    // ------------------------------------------------------------------ conexão

    private Node connection() {
        var left = new VBox(3, colored(new Label("Situação da conexão de e-mail"), "#365C36", true),
                wrap(colored(text(model.connectionStatus), "#42643E", false)));
        var reports = button("Continuar para relatórios", "secondary", model::continueToReports);
        reports.disableProperty().bind(model.canOpenReports().not());
        var row = new HBox(12, grow(left), reports);
        row.setAlignment(Pos.CENTER_LEFT);
        return boxed(row, "#EDF5EA", "#BED5BA", 10, "12");
    }
}
