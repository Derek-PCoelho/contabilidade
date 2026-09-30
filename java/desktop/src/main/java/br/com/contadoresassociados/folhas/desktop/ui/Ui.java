package br.com.contadoresassociados.folhas.desktop.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Fábricas curtas para os elementos do layout — cada método corresponde a uma classe de estilo
 * de {@code MainWindow.axaml} ({@code h1}, {@code h2}, {@code eyebrow}, {@code card}, ...).
 */
public final class Ui {

    private Ui() {
    }

    public static Label text(String value, String... styleClasses) {
        var label = new Label(value);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    public static Label text(ObservableValue<String> value, String... styleClasses) {
        var label = new Label();
        label.textProperty().bind(value);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    public static Label wrap(Label label) {
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    public static Label h1(ObservableValue<String> value) {
        return text(value, "h1");
    }

    public static Label h2(String value) {
        return wrap(text(value, "h2"));
    }

    public static Label h2(ObservableValue<String> value) {
        return wrap(text(value, "h2"));
    }

    public static Label eyebrow(String value) {
        return text(value, "eyebrow");
    }

    public static Label muted(String value) {
        return wrap(text(value, "muted"));
    }

    public static Label muted(ObservableValue<String> value) {
        return wrap(text(value, "muted"));
    }

    public static Label fieldLabel(String value) {
        return text(value, "label-field");
    }

    public static Label fieldLabel(ObservableValue<String> value) {
        return text(value, "label-field");
    }

    /** Cabeçalho de etapa: "1 · DADOS ESSENCIAIS", título e explicação. */
    public static VBox heading(String eyebrow, String title, String description) {
        var box = new VBox(3);
        if (eyebrow != null) {
            box.getChildren().add(eyebrow(eyebrow));
        }
        box.getChildren().add(h2(title));
        if (description != null) {
            box.getChildren().add(muted(description));
        }
        return box;
    }

    public static VBox card(double spacing, Node... children) {
        var box = new VBox(spacing, children);
        box.getStyleClass().add("card");
        return box;
    }

    public static VBox soft(double spacing, Node... children) {
        var box = new VBox(spacing, children);
        box.getStyleClass().add("soft");
        return box;
    }

    public static VBox stack(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    public static VBox field(String label, Node control) {
        var box = new VBox(fieldLabel(label), control);
        return box;
    }

    public static VBox field(ObservableValue<String> label, Node control) {
        return new VBox(fieldLabel(label), control);
    }

    public static HBox row(double spacing, Node... children) {
        var box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static Region spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    public static Button button(String text, String styleClass, Runnable action) {
        var button = new Button(text);
        if (styleClass != null) {
            button.getStyleClass().add(styleClass);
        }
        button.setOnAction(e -> action.run());
        return button;
    }

    public static Button button(ObservableValue<String> text, String styleClass, Runnable action) {
        var button = button("", styleClass, action);
        button.textProperty().bind(text);
        return button;
    }

    public static Button stretch(Button button) {
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    /** Grade com N colunas proporcionais (equivalente a {@code ColumnDefinitions="*,*"}). */
    public static GridPane columns(double hgap, double vgap, double... weights) {
        var grid = new GridPane();
        grid.setHgap(hgap);
        grid.setVgap(vgap);
        var total = 0.0;
        for (var weight : weights) {
            total += weight;
        }
        for (var weight : weights) {
            var column = new ColumnConstraints();
            column.setPercentWidth(weight / total * 100);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            grid.getColumnConstraints().add(column);
        }
        return grid;
    }

    public static TitledPane expander(String header, Node content, boolean expanded) {
        var pane = new TitledPane(header, content);
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        pane.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    public static TitledPane expander(ObservableValue<String> header, Node content, boolean expanded) {
        var pane = expander("", content, expanded);
        pane.textProperty().bind(header);
        return pane;
    }

    /** {@code IsVisible} do Avalonia: esconde e remove do layout. */
    public static <T extends Node> T visibleWhen(T node, ObservableBooleanValue condition) {
        node.visibleProperty().bind(condition);
        node.managedProperty().bind(node.visibleProperty());
        return node;
    }

    public static <T extends Node> T hiddenWhen(T node, ObservableBooleanValue condition) {
        return visibleWhen(node, Bindings.not(condition));
    }

    public static ScrollPane scroll(Node content, double margin) {
        var holder = new StackPane(content);
        holder.setPadding(new Insets(margin));
        holder.setAlignment(Pos.TOP_LEFT);
        var scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    public static Region divider() {
        var region = new Region();
        region.getStyleClass().add("divider");
        return region;
    }
}
