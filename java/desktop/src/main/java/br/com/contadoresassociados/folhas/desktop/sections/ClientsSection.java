package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog;
import br.com.contadoresassociados.folhas.desktop.state.ClientsModel;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Cadastro de clientes — mesma sequência de cartões e expansores de {@code MainWindow.axaml}. */
public final class ClientsSection implements Section {

    private final ClientsModel model;
    private final Node root;
    private boolean loaded;

    public ClientsSection(ClientsModel model) {
        this.model = model;
        var editor = new VBox(14, editorHeader(),
                visibleWhen(new VBox(14, feedback(), essentials(), partners(), delivery(), establishments(),
                        identifiers(), templates(), notes(), finish()), model.clientEditorPanelExpanded));
        editor.setStyle("-fx-padding: 0 4 16 0;");
        root = scroll(new VBox(14, clientList(), editor), 20);
        root.setAccessibleText("Cadastro de clientes");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShown() {
        if (!loaded) {
            loaded = true;
            model.loadClients();
        }
    }

    // ------------------------------------------------------------------ lista

    private Node clientList() {
        var header = new HBox(10,
                grow(new VBox(h2("Clientes cadastrados"), muted("Clique em um cliente para abrir e atualizar o cadastro."))),
                button(model.clientListPanelActionLabel(), "ghost", model::toggleClientListPanel),
                button("Novo cliente", "primary", model::newClient));
        header.setAlignment(Pos.CENTER_LEFT);

        var search = textField(model.searchText, "Buscar por nome, CPF ou CNPJ e pressionar Enter");
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                model.loadClients();
            }
        });
        var inactive = checkBox("Mostrar inativos", model.includeInactive);
        var mask = checkBox("Ocultar CPF/CNPJ na lista", model.maskClientTaxIds);
        var filters = new HBox(16, inactive, mask, spacer(), button("Buscar", "ghost", model::loadClients));
        filters.setAlignment(Pos.CENTER_LEFT);

        var list = listView(model.clients, model.selectedClient, row -> {
            var name = text(row.displayName(), "row-title");
            var status = row.isActive() ? text("Ativo", "tag-active") : text("Inativo", "tag-inactive");
            var detail = new HBox(text(row.taxIdDisplay(), "row-sub-small"), spacer(), status);
            return rowBox(new VBox(3, name, detail));
        }, 72);
        list.setMaxHeight(230);
        list.setAccessibleText("Clientes cadastrados");
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> model.openSelectedClientIfDifferent());
        var body = visibleWhen(new VBox(10, search, filters, list), model.clientListPanelExpanded);
        var card = card(10, header, body);
        card.setStyle("-fx-padding: 14;");
        return card;
    }

    // ------------------------------------------------------------------ cabeçalho do editor

    private Node editorHeader() {
        var title = new VBox(h2(model.clientEditorTitle()), muted(model.readinessMessage));
        var head = new HBox(10, grow(title), button(model.clientEditorPanelActionLabel(), "ghost", model::toggleClientEditorPanel));
        head.setAlignment(Pos.CENTER_LEFT);

        var statusPill = new HBox(text(model.clientStatusLabel(), "strong"));
        statusPill.getStyleClass().add("pill");
        statusPill.styleProperty().bind(Bindings.when(model.clientActive)
                .then("-fx-background-color: #EAF4E8; -fx-background-radius: 14; -fx-padding: 7 11 7 11;")
                .otherwise("-fx-background-color: #F2EEE7; -fx-background-radius: 14; -fx-padding: 7 11 7 11;"));
        ((Label) statusPill.getChildren().getFirst()).styleProperty().bind(Bindings.when(model.clientActive)
                .then("-fx-text-fill: #2F6D3B;").otherwise("-fx-text-fill: #6E6254;"));
        var actions = new FlowPane(8, 8,
                visibleWhen(statusPill, model.existingClient),
                visibleWhen(button(model.clientArchiveActionLabel(), "danger", model::archiveClient), model.existingClient),
                visibleWhen(button(model.clientStatusActionLabel(), "ghost", model::toggleClientActive), model.existingClient),
                disabledWhileSaving(button(model.clientSaveActionLabel(), "primary", model::saveClient)));
        actions.setAlignment(Pos.CENTER_RIGHT);
        return card(11, head, visibleWhen(actions, model.clientEditorPanelExpanded));
    }

    private Node feedback() {
        var icon = new Label();
        icon.textProperty().bind(Bindings.when(model.feedbackIsError).then("!").otherwise("✓"));
        var message = wrap(new Label());
        message.textProperty().bind(model.feedbackMessage);
        var colors = Bindings.when(model.feedbackIsError).then("-fx-text-fill: #7A3328;").otherwise("-fx-text-fill: #315E36;");
        icon.styleProperty().bind(Bindings.concat(colors, "-fx-font-weight: bold;"));
        message.styleProperty().bind(colors);
        var box = new HBox(9, icon, message);
        HBox.setHgrow(message, Priority.ALWAYS);
        box.setAlignment(Pos.CENTER_LEFT);
        box.styleProperty().bind(Bindings.when(model.feedbackIsError)
                .then("-fx-background-color: #FFF0ED; -fx-border-color: #D99A8C; -fx-border-width: 1; -fx-background-radius: 11; -fx-border-radius: 11; -fx-padding: 10 13 10 13;")
                .otherwise("-fx-background-color: #EDF5EA; -fx-border-color: #A8C9A5; -fx-border-width: 1; -fx-background-radius: 11; -fx-border-radius: 11; -fx-padding: 10 13 10 13;"));
        box.setAccessibleText("Resultado do cadastro");
        return visibleWhen(box, model.hasFeedback());
    }

    // ------------------------------------------------------------------ 1 · dados essenciais

    private Node essentials() {
        var type = comboBox(FXCollections.observableArrayList(ClientsModel.PERSON_TYPES), model.personType);
        type.disableProperty().bind(model.existingClient);
        var taxId = textField(model.primaryTaxId, "");
        taxId.promptTextProperty().bind(model.primaryTaxIdPlaceholder());
        var taxBox = field(model.primaryTaxIdLabel(), taxId);
        var help = muted(model.primaryTaxIdHelp());
        help.setStyle("-fx-padding: 5 0 0 0;");
        taxBox.getChildren().add(help);

        var grid = columns(14, 12, 1, 1);
        grid.add(field("Tipo de cliente", type), 0, 0);
        grid.add(taxBox, 1, 0);
        grid.add(field(model.legalNameLabel(), textField(model.legalName, "")), 0, 1, 2, 1);
        grid.add(visibleWhen(field("Nome fantasia (opcional)", textField(model.preferredName, "Ex.: Loja Primavera")), model.isLegalEntity()), 0, 2);
        grid.add(visibleWhen(field("Código no sistema contábil (opcional)", textField(model.internalCode, "")), model.isLegalEntity()), 1, 2);
        GridPane.setValignment(taxBox, javafx.geometry.VPos.TOP);
        return card(14, heading("1 · DADOS ESSENCIAIS", "Quem é este cliente?",
                "Estes são os únicos dados obrigatórios para identificar corretamente a empresa ou pessoa."), grid);
    }

    // ------------------------------------------------------------------ sócios

    private Node partners() {
        var grid = columns(10, 9, 1, 1);
        grid.add(textField(model.newPartnerName, "Nome completo do sócio ou representante"), 0, 0);
        grid.add(textField(model.newPartnerCpf, "CPF (opcional)"), 1, 0);
        grid.add(textField(model.newPartnerEmail, "E-mail do representante (opcional)"), 0, 1);
        grid.add(comboBox(FXCollections.observableArrayList(ClientsModel.PARTNER_ROLES), model.partnerRole), 1, 1);
        grid.add(checkBox("Usar também como contato de entrega", model.usePartnerAsDeliveryContact), 0, 2, 2, 1);
        var error = new HBox(wrap(text(model.partnerInputError, "strong")));
        error.getStyleClass().add("error-box");
        grid.add(visibleWhen(error, model.hasPartnerInputError()), 0, 3, 2, 1);
        var add = button("Adicionar representante", "primary", model::addPartner);
        grid.add(add, 1, 4);
        GridPane.setHalignment(add, javafx.geometry.HPos.RIGHT);

        var list = listView(model.partners, model.selectedPartner, (ClientPartnerModel p) -> {
            var top = new HBox(10, text(p.fullName(), "row-title"), spacer(), text(FriendlyText.of(p.role()), "row-accent"));
            var bottom = new HBox(10, text(p.email() == null ? "E-mail não informado" : p.email(), "row-sub"), spacer(),
                    text(p.cpf() == null ? "CPF não informado" : p.cpf(), "row-sub"));
            return softBox(new VBox(top, bottom));
        }, 72);
        list.setMaxHeight(170);
        var card = card(12, heading("SÓCIOS E REPRESENTANTES · OPCIONAL", "Quem representa esta empresa?",
                "Informe somente quando for útil ao cadastro. O CPF do sócio nunca será usado para associar um documento à empresa."),
                grid, list, right(button("Remover selecionado", "ghost", model::removePartner)));
        return visibleWhen(card, model.isLegalEntity());
    }

    // ------------------------------------------------------------------ 2 · contato de entrega

    private Node delivery() {
        var grid = columns(10, 9, 1, 1);
        grid.add(textField(model.newRecipientName, "Nome do contato ou setor"), 0, 0);
        grid.add(textField(model.newRecipientEmail, "email@empresa.com.br"), 1, 0);
        grid.add(comboBox(FXCollections.observableArrayList(ClientsModel.DELIVERY_ROLES), model.deliveryRole), 0, 1);
        var add = button("Adicionar contato", "primary", model::addRecipient);
        grid.add(add, 1, 1);
        GridPane.setHalignment(add, javafx.geometry.HPos.RIGHT);
        var list = listView(model.recipients, model.selectedRecipient, (RecipientModel r) -> {
            var top = new HBox(10, text(r.displayName(), "row-title"), spacer(), text(FriendlyText.of(r.deliveryRole()), "row-accent"));
            var bottom = new HBox(10, text(r.email(), "row-sub"), spacer());
            if (r.isPrimary()) {
                bottom.getChildren().add(text("E-mail principal", "tag-active"));
            }
            return softBox(new VBox(3, top, bottom));
        }, 72);
        list.setMaxHeight(200);
        return card(13, heading("2 · CONTATO DE ENTREGA", "Quem recebe os documentos?",
                "Na maioria dos casos, basta o e-mail da própria empresa. Adicione outros somente quando houver uma necessidade real de cópia."),
                grid, list, right(button("Remover contato selecionado", "ghost", model::removeRecipient)));
    }

    // ------------------------------------------------------------------ expansores

    private Node establishments() {
        var grid = columns(8, 8, 2, 3);
        grid.add(textField(model.newEstablishmentCnpj, "CNPJ da unidade"), 0, 0);
        grid.add(textField(model.newEstablishmentName, "Nome da matriz ou filial"), 1, 0);
        var add = button("Adicionar unidade", "primary", model::addEstablishment);
        grid.add(add, 0, 1, 2, 1);
        GridPane.setHalignment(add, javafx.geometry.HPos.RIGHT);
        var list = listView(model.establishments, model.selectedEstablishment, (EstablishmentModel e) -> {
            var cnpj = text(e.cnpj(), "row-accent");
            cnpj.setMinWidth(220);
            var row = new HBox(cnpj, text(e.displayName(), "row-title-plain"), spacer());
            if (e.isHeadOffice()) {
                row.getChildren().add(text("Matriz", "tag-active"));
            }
            return softBox(row);
        }, 72);
        list.setMaxHeight(190);
        var content = new VBox(12, muted("Use esta seção quando um mesmo cliente possui matriz e filiais com CNPJs próprios. Isso evita associar o documento à unidade errada."),
                grid, list, right(button("Remover unidade selecionada", "ghost", model::removeEstablishment)));
        return visibleWhen(expander("Filiais ou estabelecimentos (somente se houver)", content, false), model.isLegalEntity());
    }

    private Node identifiers() {
        var type = comboBox(FXCollections.observableArrayList(ClientsModel.IDENTIFIER_TYPES), model.identifierType);
        type.setPrefWidth(220);
        var value = textField(model.newIdentifierValue, "Nome alternativo ou código");
        HBox.setHgrow(value, Priority.ALWAYS);
        var row = new HBox(8, type, value, button("Adicionar", "primary", model::addIdentifier));
        row.setAlignment(Pos.CENTER_LEFT);
        var list = listView(model.identifiers, model.selectedIdentifier, (ClientIdentifierModel i) -> {
            var kind = text(FriendlyText.of(i.type()), "row-accent");
            kind.setMinWidth(240);
            return softBox(new HBox(kind, text(i.value(), "row-title-plain")));
        }, 72);
        list.setMaxHeight(170);
        var content = new VBox(12, muted("Cadastre aqui apenas aliases ou códigos adicionais que realmente aparecem nos documentos. O CPF ou CNPJ principal já foi informado acima."),
                row, list, right(button("Remover item selecionado", "ghost", model::removeIdentifier)));
        return expander("Outros nomes e códigos usados nos documentos (opcional)", content, false);
    }

    private Node templates() {
        var presets = new FlowPane(8, 8,
                button("Usar padrão para empresa", "ghost", () -> model.applyStandardTemplate(PersonTypeModel.LEGAL_ENTITY)),
                button("Usar padrão para pessoa física", "ghost", () -> model.applyStandardTemplate(PersonTypeModel.INDIVIDUAL)));
        var presetBox = new VBox(8, text("Prefere começar com uma mensagem pronta?", "strong"),
                muted("Escolha um texto neutro e profissional; depois você pode ajustar somente o necessário."), presets);
        presetBox.getStyleClass().add("preset-box");

        var name = limited(textField(model.templateName, ""), 160);
        var subject = limited(textField(model.templateSubject, ""), 500);
        var body = new TextArea();
        body.textProperty().bindBidirectional(model.templateBody);
        body.setWrapText(true);
        body.setPrefRowCount(8);
        body.setMinHeight(170);
        limited(body, 20_000);

        var target = comboBox(FXCollections.observableArrayList(ClientsModel.PLACEHOLDER_TARGETS), model.placeholderTarget);
        target.setPromptText("Escolha onde inserir: assunto ou texto");
        var placeholders = new FlowPane(6, 6,
                placeholder("Nome do cliente", MessageTemplatePlaceholderCatalog.CLIENT_PREFERRED_OR_LEGAL_NAME),
                placeholder("Nome do contato", MessageTemplatePlaceholderCatalog.CONTACT_NAME),
                placeholder("Competência", MessageTemplatePlaceholderCatalog.PERIOD_LABEL),
                placeholder("Lista de documentos", MessageTemplatePlaceholderCatalog.DOCUMENT_LIST),
                placeholder("Vencimentos", MessageTemplatePlaceholderCatalog.DUE_DATE_LIST),
                placeholder("Nome do escritório", MessageTemplatePlaceholderCatalog.OFFICE_NAME));

        var actions = columns(8, 0, 1, 1);
        actions.add(stretch(button("Começar nova", "ghost", model::newTemplate)), 0, 0);
        actions.add(stretch(button(model.templateSaveActionLabel(), "primary", model::saveTemplate)), 1, 0);

        var editor = new VBox(10, h2(model.templateEditorTitle()),
                muted("Use somente quando este cliente precisa de uma mensagem diferente do padrão do escritório. O nome abaixo serve apenas para você localizar o modelo."),
                presetBox,
                counted("Nome da mensagem", name, model.templateName, "{0}/160 caracteres"),
                counted("Assunto do e-mail", subject, model.templateSubject, "{0}/500 caracteres"),
                new VBox(6, fieldLabel("Adicionar informação variável sem decorar códigos"), target, placeholders),
                counted("Texto do e-mail", body, model.templateBody, "{0}/20.000 caracteres"),
                actions);

        var list = listView(model.templates, model.selectedTemplate, (MessageTemplateModel t) -> {
            var top = new HBox(8, text(t.name(), "row-title"), spacer(),
                    t.isActive() ? text("Ativa", "tag-active-small") : text("Inativa", "tag-inactive-small"));
            return softBox(new VBox(top, text(t.subjectTemplate(), "row-sub")));
        }, 72);
        list.setMaxHeight(420);
        var toggle = button(model.templateStatusActionLabel(), "ghost", model::toggleTemplateActive);
        toggle.disableProperty().bind(model.hasSelectedTemplate().not());
        var archive = button(model.templateArchiveActionLabel(), "danger", model::archiveTemplate);
        archive.disableProperty().bind(model.hasSelectedTemplate().not());
        var listActions = new HBox(8, button("Editar selecionada", "ghost", model::editSelectedTemplate), toggle, archive);
        listActions.setAlignment(Pos.CENTER_RIGHT);
        var saved = new VBox(9, h2("Mensagens deste cliente"),
                muted("Selecionar não altera nada. Use Editar para carregar uma mensagem no formulário."), list, listActions);
        return expander("Mensagem personalizada para este cliente (opcional)", new VBox(16, editor, saved), false);
    }

    private Node notes() {
        var area = new TextArea();
        area.textProperty().bindBidirectional(model.notes);
        area.setWrapText(true);
        area.setPrefRowCount(3);
        area.setMinHeight(86);
        area.setPromptText("Informações úteis para o escritório; não serão incluídas automaticamente no e-mail.");
        return card(7, fieldLabel("Observações internas (opcional)"), area);
    }

    private Node finish() {
        var text = new VBox(h2("Concluir cadastro"), muted("Confira os dados acima. Campos opcionais podem permanecer em branco."));
        var row = new HBox(10, grow(text),
                visibleWhen(button(model.clientArchiveActionLabel(), "danger", model::archiveClient), model.existingClient),
                disabledWhileSaving(button(model.clientSaveActionLabel(), "primary", model::saveClient)));
        row.setAlignment(Pos.CENTER_LEFT);
        return card(0, row);
    }

    // ------------------------------------------------------------------ apoio

    private Button placeholder(String label, String key) {
        return button(label, "ghost", () -> model.insertPlaceholder(key));
    }

    private Button disabledWhileSaving(Button button) {
        button.disableProperty().bind(model.saving);
        return button;
    }

    private static VBox counted(String label, javafx.scene.control.Control control, StringProperty value, String format) {
        var counter = new Label();
        counter.textProperty().bind(Bindings.createStringBinding(
                () -> format.replace("{0}", Integer.toString(value.get() == null ? 0 : value.get().length())), value));
        counter.getStyleClass().addAll("muted", "counter");
        var counterRow = new HBox(counter);
        counterRow.setAlignment(Pos.CENTER_RIGHT);
        return new VBox(fieldLabel(label), control, counterRow);
    }

    private static <T extends javafx.scene.control.TextInputControl> T limited(T control, int max) {
        control.setTextFormatter(new javafx.scene.control.TextFormatter<String>(change ->
                change.getControlNewText().length() <= max ? change : null));
        return control;
    }

}
