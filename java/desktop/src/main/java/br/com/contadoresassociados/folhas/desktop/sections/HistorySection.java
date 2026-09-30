package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.desktop.state.HistoryModel;
import br.com.contadoresassociados.folhas.desktop.ui.FriendlyText;
import java.time.LocalDate;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.StringConverter;

/** Histórico e ocorrências — linha do tempo, filtros, limpeza da visualização e ocorrências. */
public final class HistorySection implements Section {

    private final HistoryModel model;
    private final Node root;

    public HistorySection(HistoryModel model) {
        this.model = model;
        var truncation = new HBox(wrap(colored(text(model.truncationMessage), "#694B10", true)));
        boxed(truncation, "#FFF6DE", "#E0C578", 10, "12");
        var content = new VBox(14, intro(), filters(), visibleWhen(truncation, model.truncated), cleanup(),
                timeline(model.reviewHeader(), model.reviewExpanded,
                        "Importações, conferências, correções e liberações documentais no recorte aplicado.", model.reviewRows),
                timeline(model.dispatchHeader(), model.dispatchExpanded,
                        "Preparação, aprovação, operação no serviço de e-mail e resultado registrado.", model.dispatchRows),
                catalog(), incidents());
        root = scroll(content, 28);
        root.setAccessibleText("Histórico e ocorrências");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void onShown() {
        model.open();
    }

    private Node intro() {
        return card(5, eyebrow("LINHA DO TEMPO"), h1(new SimpleStringProperty("O que aconteceu no trabalho")),
                muted("Veja as ações do dia a dia em linguagem simples. Filtre por data, cliente ou documento e abra somente a seção que precisa consultar."));
    }

    // ------------------------------------------------------------------ filtros

    private Node filters() {
        var scope = comboBox(model.timeScopes, model.timeScope);
        scope.setAccessibleText("Quando aconteceu");
        var day = visibleWhen(field("Dia", date(model.filterStartDate)), model.isFilterDay());
        var month = columns(10, 0, 1, 1);
        month.add(field("Ano", combo(model.calendarYears, model.calendarYear)), 0, 0);
        month.add(field("Mês", combo(model.calendarMonths, model.calendarMonth)), 1, 0);
        visibleWhen(month, model.isFilterMonth());
        var year = visibleWhen(field("Ano", combo(model.calendarYears, model.calendarYear)), model.isFilterYear());
        var custom = columns(10, 0, 1, 1);
        custom.add(new VBox(4, fieldLabel("Começa em"), date(model.filterStartDate), time(model.filterStartTime)), 0, 0);
        custom.add(new VBox(4, fieldLabel("Termina em"), date(model.filterEndDate), time(model.filterEndTime)), 1, 0);
        visibleWhen(custom, model.isFilterCustom());
        var when = soft(8, fieldLabel("Quando aconteceu?"), scope, muted(model.timeScopeHelp()), day, month, year, custom);

        var client = comboBox(model.clientFilters, model.clientFilter);
        client.setAccessibleText("Cliente");
        var document = comboBox(model.documentFilters, model.documentFilter);
        document.setAccessibleText("Documento");
        var search = textField(model.searchText, "Ex.: rescisão, BOREAL ou nome do arquivo");
        search.setAccessibleText("Palavra ou referência");
        var searchLabel = fieldLabel("Palavra ou referência (opcional)");
        searchLabel.setStyle(searchLabel.getStyle() + " -fx-padding: 4 0 0 0;");
        var who = soft(8, fieldLabel("De quem e sobre qual documento?"), client, document, searchLabel, search,
                muted("Ao escolher um cliente, as alterações do cadastro dele também ficam disponíveis na seção própria."));

        var grid = columns(14, 0, 1, 1);
        grid.add(when, 0, 0);
        grid.add(who, 1, 0);
        when.setMaxHeight(Double.MAX_VALUE);
        who.setMaxHeight(Double.MAX_VALUE);

        var error = new HBox(wrap(colored(text(model.filterError), "#7A3328", true)));
        boxed(error, "#FFF0ED", "#D99A8C", 9, "8 10 8 10");
        var summary = new HBox(wrap(colored(text(model.appliedFilterSummary), "#694B10", true)));
        summary.setAlignment(Pos.CENTER_LEFT);
        boxed(summary, "#FFF6DE", "#E0C578", 10, "9 11 9 11");
        var apply = button("Aplicar filtros", "primary", model::applyFilters);
        var clear = button("Limpar filtros", "ghost", model::clearFilters);
        apply.setMaxHeight(Double.MAX_VALUE);
        clear.setMaxHeight(Double.MAX_VALUE);
        var actions = new HBox(10, grow(summary), apply, clear);
        actions.setAlignment(Pos.CENTER_LEFT);

        return card(12, new VBox(4, eyebrow("FILTROS DE CONSULTA"), h2("Encontre um acontecimento sem percorrer toda a lista"),
                        muted("A competência do cabeçalho continua valendo. Estes filtros refinam a consulta do Histórico e não apagam nenhum registro.")),
                grid, visibleWhen(error, model.hasFilterError()), actions);
    }

    private static DatePicker date(ObjectProperty<LocalDate> value) {
        var picker = new DatePicker();
        picker.valueProperty().bindBidirectional(value);
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setConverter(new StringConverter<>() {
            private final java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

            @Override
            public String toString(LocalDate d) {
                return d == null ? "" : d.format(format);
            }

            @Override
            public LocalDate fromString(String s) {
                try {
                    return s == null || s.isBlank() ? null : LocalDate.parse(s.strip(), format);
                } catch (java.time.format.DateTimeParseException e) {
                    return null;
                }
            }
        });
        picker.setPromptText("dd/mm/aaaa");
        return picker;
    }

    /** Campo de horário 24 h (equivalente ao {@code TimePicker ClockIdentifier=24HourClock}). */
    private static Node time(StringProperty value) {
        var field = textField(value, "HH:mm");
        field.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("[0-9:]{0,5}") ? change : null));
        field.setAccessibleText("Horário (24 horas)");
        return field;
    }

    private static <T> ComboBox<T> combo(ObservableList<T> items, ObjectProperty<T> value) {
        var combo = new ComboBox<>(items);
        combo.valueProperty().bindBidirectional(value);
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    // ------------------------------------------------------------------ limpar ou restaurar

    private Node cleanup() {
        var warning = new HBox(wrap(colored(new Label("Esta ação somente esconde registros da tela. A auditoria permanece íntegra, protegida e recuperável."),
                "#694B10", false)));
        boxed(warning, "#FFF6DE", "#E0C578", 10, "12");
        var scope = comboBox(model.cleanupScopes, model.cleanupScope);
        var scopeBox = new VBox(5, fieldLabel("Qual parte da linha do tempo deseja ocultar?"), scope, muted(model.cleanupHelp()));
        scopeBox.setMaxWidth(620);
        var day = visibleWhen(field("Escolha o dia", date(model.rangeStartDate)), model.isDayCleanup());
        day.setMaxWidth(320);
        var hour = columns(10, 0, 1, 1);
        hour.add(field("Escolha o dia", date(model.rangeStartDate)), 0, 0);
        hour.add(field("Escolha o bloco de uma hora", combo(model.hourOptions, model.cleanupHour)), 1, 0);
        hour.setMaxWidth(620);
        visibleWhen(hour, model.isHourCleanup());
        var custom = columns(14, 0, 1, 1);
        custom.add(new VBox(5, fieldLabel("Começa em"), date(model.rangeStartDate), time(model.rangeStartTime)), 0, 0);
        custom.add(new VBox(5, fieldLabel("Termina em"), date(model.rangeEndDate), time(model.rangeEndTime)), 1, 0);
        custom.setMaxWidth(760);
        visibleWhen(custom, model.isCustomCleanup());
        var summary = new HBox(wrap(colored(text(model.cleanupSelectionSummary()), "#514C42", true)));
        boxed(summary, "#F3EFE6", null, 9, "9 11 9 11");
        var restore = button("Restaurar todo o histórico", "ghost", model::restoreView);
        restore.disableProperty().bind(model.hasHidden.not());
        var show = new CheckBox("Mostrar temporariamente os registros ocultos");
        show.selectedProperty().bindBidirectional(model.showHidden);
        visibleWhen(show, model.hasHidden);
        var actions = new FlowPane(8, 8, button(model.cleanupActionLabel(), "secondary", model::archiveView), restore, show);
        actions.setAlignment(Pos.CENTER_LEFT);
        var body = card(12, warning, scopeBox, day, hour, custom, summary, actions,
                visibleWhen(muted(model.hiddenCountText), model.hasHidden));
        return expander("Limpar ou restaurar a visualização", body, false);
    }

    // ------------------------------------------------------------------ linhas do tempo

    private Node timeline(javafx.beans.value.ObservableValue<String> header, javafx.beans.property.BooleanProperty expanded,
            String description, ObservableList<HistoryModel.TimelineRow> rows) {
        var list = itemsControl(rows, this::row, 6);
        var pane = expander(header, new VBox(10, muted(description), list), expanded.get());
        pane.expandedProperty().bindBidirectional(expanded);
        return pane;
    }

    private Node row(HistoryModel.TimelineRow r) {
        var when = colored(new Label(r.timestamp()), "#777064", false);
        when.setStyle(when.getStyle() + " -fx-font-size: 12px;");
        var action = wrap(colored(new Label(r.action()), r.needsAttention() ? "#96523D" : "#8C691B", true));
        var head = new FlowPane(12, 2, when, action);
        var result = wrap(colored(new Label(r.result()), "#716B60", false));
        result.setStyle(result.getStyle() + " -fx-font-size: 12px;");
        var box = soft(4, head, wrap(colored(new Label(r.clientName()), "#29261F", true)),
                wrap(colored(new Label(r.context()), "#514C42", false)));
        if (!r.result().isBlank()) {
            box.getChildren().add(result);
        }
        return box;
    }

    private Node catalog() {
        var empty = new Label();
        empty.textProperty().bind(model.catalogEmptyMessage());
        wrap(empty).getStyleClass().add("muted");
        empty.setTextAlignment(TextAlignment.CENTER);
        empty.setMaxWidth(Double.MAX_VALUE);
        empty.setAlignment(Pos.CENTER);
        var list = itemsControl(model.catalogRows, this::row, 6);
        return expander(model.catalogHeader(), new VBox(10,
                muted("Esta seção permanece separada porque alterações cadastrais têm finalidade e retenção diferentes das operações com documentos e mensagens."),
                hiddenWhen(soft(0, empty), model.hasCatalogRows()), list), false);
    }

    // ------------------------------------------------------------------ ocorrências

    private Node incidents() {
        var attempt = new ComboBox<>(model.attempts);
        attempt.valueProperty().bindBidirectional(model.incidentAttempt);
        attempt.setPromptText("Selecione uma tentativa");
        attempt.setMaxWidth(Double.MAX_VALUE);
        attempt.setAccessibleText("Tentativa relacionada");
        attempt.setConverter(new StringConverter<>() {
            @Override
            public String toString(DeliveryAttempt a) {
                return HistoryModel.attemptLabel(a, model.zone());
            }

            @Override
            public DeliveryAttempt fromString(String s) {
                throw new UnsupportedOperationException();
            }
        });
        var classification = columns(8, 0, 1, 1);
        classification.add(field("Tipo de problema", comboBox(model.incidentCategories, model.incidentCategory)), 0, 0);
        classification.add(field("Urgência", comboBox(model.incidentSeverities, model.incidentSeverity)), 1, 0);
        var summary = textArea(model.incidentSummary, "Descreva o fato e a ação imediata, sem colar documentos ou credenciais", 82);
        limit(summary);
        var report = new VBox(9, eyebrow("NOVO RELATO"), h2("O que aconteceu?"),
                muted("Use esta área quando uma tentativa tiver resultado incerto. Não repita a ação até conferir o ocorrido."),
                fieldLabel("Tentativa relacionada"), attempt, classification, summary,
                button("Registrar problema", "primary", model::openIncident));

        var list = listView(model.incidents, model.selectedIncident, i -> {
            var title = text(i.summary(), "row-title");
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            var left = new VBox(title, muted(FriendlyText.of(i.category())));
            HBox.setHgrow(left, Priority.ALWAYS);
            left.setMinWidth(0);
            return softBox(new HBox(8, left, colored(new Label(FriendlyText.of(i.status())), "#8C691B", false)));
        }, 64);
        list.setMaxHeight(180);
        list.setAccessibleText("Problemas registrados");
        var note = textArea(model.incidentNote, "Informe a providência ou solução aplicada", 82);
        limit(note);
        var follow = new VBox(9, eyebrow("ACOMPANHAMENTO"), h2("Providências e solução"), list, fieldLabel("Nova situação"),
                comboBox(model.incidentStatuses, model.incidentStatus), note,
                button("Atualizar situação", "secondary", model::transitionIncident),
                muted("O histórico não pode ser apagado. Dados fiscais, e-mails e segredos digitados no relato são ocultados antes de salvar."));

        var pane = expander("Relatar ou acompanhar um problema", new VBox(18, report, divider(), follow), false);
        pane.expandedProperty().bindBidirectional(model.incidentsExpanded);
        pane.setAccessibleText("Registro e acompanhamento de ocorrências");
        return pane;
    }

    private static void limit(javafx.scene.control.TextArea area) {
        area.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 2000 ? change : null));
    }
}
