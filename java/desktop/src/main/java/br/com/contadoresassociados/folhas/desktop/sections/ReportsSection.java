package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Controls.*;
import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.desktop.state.OperationalPeriod;
import br.com.contadoresassociados.folhas.desktop.state.ReportsModel;
import java.io.File;
import java.nio.file.Path;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;

/** Relatórios — recorte, situação das comunicações e arquivos gerados (mesma ordem de {@code MainWindow.axaml}). */
public final class ReportsSection implements Section {

    private final ReportsModel model;
    private final Node root;

    public ReportsSection(ReportsModel model) {
        this.model = model;
        var content = new VBox(16, intro(), scope(), communications(), files());
        content.setMaxWidth(1080);
        var centered = new HBox(content);
        centered.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(content, Priority.ALWAYS);
        root = scroll(centered, 28);
        root.setAccessibleText("Relatórios");
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
        var card = card(7, eyebrow("PRESTAÇÃO DE CONTAS"), h1(new javafx.beans.property.SimpleStringProperty(
                "Relatório claro para conferência")),
                muted("Escolha o recorte aqui sem alterar a competência de trabalho do restante do aplicativo. A planilha serve para análise detalhada e o PDF para leitura e arquivo."));
        card.setStyle("-fx-padding: 24;");
        return card;
    }

    // ------------------------------------------------------------------ 1 · recorte

    private Node scope() {
        var clientFilter = comboBox(model.clientFilters, model.clientFilter);
        clientFilter.setAccessibleText("Quem deve aparecer");
        var client = new ComboBox<>(model.clients);
        client.valueProperty().bindBidirectional(model.client);
        client.setPromptText("Escolha o cliente");
        client.setMaxWidth(Double.MAX_VALUE);
        client.setAccessibleText("Cliente do relatório");
        var who = soft(8, fieldLabel("1. Quem deve aparecer?"), clientFilter, visibleWhen(client, model.isClientScope()),
                muted(model.clientFilterHelp()));

        var scope = comboBox(model.scopes, model.scope);
        scope.setAccessibleText("Período do relatório");
        var month = columns(10, 0, 1, 1);
        month.add(field("Ano", year(model.year)), 0, 0);
        month.add(field("Mês", month(model.month)), 1, 0);
        var yearOnly = field("Ano", year(model.year));
        yearOnly.setMaxWidth(330);
        var start = columns(10, 0, 1, 1);
        start.add(field("Ano inicial", year(model.startYear)), 0, 0);
        start.add(field("Mês inicial", month(model.startMonth)), 1, 0);
        var end = columns(10, 0, 1, 1);
        end.add(field("Ano final", year(model.endYear)), 0, 0);
        end.add(field("Mês final", month(model.endMonth)), 1, 0);
        var when = soft(8, fieldLabel("2. De qual período?"), scope, visibleWhen(month, model.isMonthScope()),
                visibleWhen(yearOnly, model.isYearScope()), visibleWhen(new VBox(8, start, end), model.isRangeScope()),
                muted(model.scopeHelp()));

        var filters = columns(14, 0, 1, 1);
        filters.add(who, 0, 0);
        filters.add(when, 1, 0);
        who.setMaxHeight(Double.MAX_VALUE);
        when.setMaxHeight(Double.MAX_VALUE);

        var summary = new HBox(wrap(colored(text(model.scopeSummary()), "#694B10", true)));
        summary.setAlignment(Pos.CENTER_LEFT);
        boxed(summary, "#FFF6DE", "#E0C578", 10, "12");
        var export = button("Gerar planilha e PDF", "primary", model::export);
        export.disableProperty().bind(model.canExport().not());
        export.setMaxHeight(Double.MAX_VALUE);
        var row = new HBox(12, grow(summary), export);
        row.setAlignment(Pos.CENTER_LEFT);

        var card = card(12, eyebrow("1 · ESCOLHA O RECORTE"), h2("Cliente e período"),
                muted("Os dois filtros funcionam juntos. Por exemplo: um cliente específico entre agosto e dezembro."), filters, row);
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private ComboBox<OperationalPeriod.YearOption> year(ObjectProperty<OperationalPeriod.YearOption> value) {
        return combo(model.yearOptions, value);
    }

    private ComboBox<OperationalPeriod.MonthOption> month(ObjectProperty<OperationalPeriod.MonthOption> value) {
        return combo(model.monthOptions, value);
    }

    private static <T> ComboBox<T> combo(ObservableList<T> items, ObjectProperty<T> value) {
        var combo = new ComboBox<>(items);
        combo.valueProperty().bindBidirectional(value);
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    // ------------------------------------------------------------------ 2 · comunicações

    private Node communications() {
        var empty = new Label("Nenhuma mensagem foi preparada neste recorte. O relatório ainda poderá registrar documentos e pendências existentes.");
        wrap(empty).getStyleClass().add("muted");
        empty.setTextAlignment(TextAlignment.CENTER);
        empty.setMaxWidth(Double.MAX_VALUE);
        empty.setAlignment(Pos.CENTER);
        var emptyBox = hiddenWhen(soft(0, empty), model.hasRows());

        var list = itemsControl(model.rows, r -> {
            var name = text(r.clientName() == null ? "" : r.clientName(), "row-title");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            var left = new VBox(2, name, muted(r.messageReference()));
            HBox.setHgrow(left, Priority.ALWAYS);
            var head = new HBox(10, left, colored(new Label(r.period()), "#766F62", false));
            return soft(5, head, wrap(colored(new Label(r.groupSummary()), "#6B5120", true)),
                    wrap(colored(new Label(r.documentSummary()), "#514C42", false)),
                    wrap(colored(new Label(r.operationResult()), r.needsAttention() ? "#96523D" : "#8C691B", true)),
                    wrap(colored(new Label(r.deliveryStatus()), "#514C42", false)), muted("Próxima ação: " + r.nextAction()));
        }, 7);

        var card = card(12, eyebrow("2 · CONFIRA AS COMUNICAÇÕES"), h2("Situação atual das mensagens no recorte"),
                muted("A tela mostra uma mensagem atual por conjunto; as ações e versões anteriores permanecem na auditoria do arquivo. “Aceita pelo serviço” não significa “entregue ao destinatário”."),
                emptyBox, list);
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    // ------------------------------------------------------------------ 3 · arquivos

    private Node files() {
        var destination = soft(8, fieldLabel("Pasta de destino"), wrap(colored(text(model.outputDirectory), "#716B60", false)),
                button("Escolher outra pasta", "ghost", this::chooseFolder));
        var last = soft(7, fieldLabel("Última planilha"), wrap(colored(text(model.lastReportPath), "#716B60", false)), divider(),
                fieldLabel("Último PDF"), wrap(colored(text(model.lastReportPdfPath), "#716B60", false)));
        var grid = columns(14, 0, 1, 1);
        grid.add(destination, 0, 0);
        grid.add(last, 1, 0);
        destination.setMaxHeight(Double.MAX_VALUE);
        last.setMaxHeight(Double.MAX_VALUE);
        var card = card(10, eyebrow("3 · ARQUIVOS GERADOS"), h2("Planilha detalhada e PDF organizado"),
                wrap(colored(new Label("A planilha contém resumo, clientes, documentos, pendências e linha do tempo. O PDF apresenta os indicadores e as situações de comunicação em formato de leitura."),
                        "#514C42", false)),
                grid, muted("Arquivos CSV auxiliares também são criados para compatibilidade com outros sistemas."));
        card.setStyle("-fx-padding: 20;");
        return card;
    }

    private void chooseFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Escolha a pasta dos relatórios");
        var current = new File(model.outputDirectory.get());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        var folder = chooser.showDialog(root.getScene().getWindow());
        if (folder != null) {
            model.chooseOutputDirectory(Path.of(folder.getAbsolutePath()));
        }
    }
}
