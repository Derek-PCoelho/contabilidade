package br.com.contadoresassociados.folhas.desktop.ui;

import java.util.function.Function;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Controles ligados a propriedades (campos, listas, combos) usados por todas as seções. */
public final class Controls {

    private Controls() {
    }

    public static TextField textField(StringProperty value, String prompt) {
        var field = new TextField();
        field.textProperty().bindBidirectional(value);
        field.setPromptText(prompt);
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    public static TextArea textArea(StringProperty value, String prompt, double minHeight) {
        var area = new TextArea();
        area.textProperty().bindBidirectional(value);
        area.setWrapText(true);
        area.setPromptText(prompt);
        area.setMinHeight(minHeight);
        area.setPrefRowCount(3);
        return area;
    }

    public static CheckBox checkBox(String label, BooleanProperty value) {
        var box = new CheckBox(label);
        box.selectedProperty().bindBidirectional(value);
        return box;
    }

    public static <T> ComboBox<T> comboBox(ObservableList<T> items, ObjectProperty<T> value) {
        var combo = new ComboBox<>(items);
        combo.valueProperty().bindBidirectional(value);
        combo.setConverter(FriendlyText.converter());
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    /** Lista "clean" com linhas desenhadas por {@code render} e seleção ligada a {@code selected}. */
    public static <T> ListView<T> listView(ObservableList<T> items, ObjectProperty<T> selected, Function<T, Node> render,
            double rowHeight) {
        var list = new ListView<>(items);
        list.getStyleClass().add("clean-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : render.apply(item));
            }
        });
        if (selected != null) {
            list.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
                if (now != null || !items.contains(selected.get())) {
                    selected.set(now);
                }
            });
            selected.addListener((obs, old, now) -> {
                if (now == null) {
                    list.getSelectionModel().clearSelection();
                } else if (list.getSelectionModel().getSelectedItem() != now) {
                    list.getSelectionModel().select(now);
                }
            });
        }
        // Altura acompanha o conteúdo até o limite (MaxHeight do Avalonia): mede as células
        // realmente desenhadas após cada layout; {@code rowHeight} é só a estimativa inicial.
        list.setPrefHeight(Math.max(56, items.size() * rowHeight + 4));
        Runnable fit = () -> {
            var total = 4.0;
            var measured = 0;
            for (var node : list.lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getItem() != null) {
                    total += cell.prefHeight(list.getWidth());
                    measured++;
                }
            }
            if (measured < items.size()) {
                total += (items.size() - measured) * rowHeight;
            }
            var target = Math.max(56, items.isEmpty() ? 56 : total);
            if (Math.abs(list.getPrefHeight() - target) > 0.5) {
                list.setPrefHeight(target);
            }
        };
        items.addListener((javafx.collections.ListChangeListener<T>) change -> javafx.application.Platform.runLater(fit));
        list.widthProperty().addListener((obs, old, now) -> javafx.application.Platform.runLater(fit));
        list.skinProperty().addListener((obs, old, now) -> javafx.application.Platform.runLater(fit));
        list.setPlaceholder(new Label(""));
        return list;
    }

    /** Lista somente leitura (equivalente a {@code ItemsControl}). */
    public static <T> VBox itemsControl(ObservableList<T> items, Function<T, Node> render, double spacing) {
        var box = new VBox(spacing);
        Runnable rebuild = () -> box.getChildren().setAll(items.stream().map(render).toList());
        items.addListener((javafx.collections.ListChangeListener<T>) change -> rebuild.run());
        rebuild.run();
        return box;
    }

    public static Node rowBox(Node content) {
        var box = new VBox(content);
        box.getStyleClass().add("list-row");
        return box;
    }

    public static Node softBox(Node content) {
        var box = new VBox(content);
        box.getStyleClass().add("soft-row");
        return box;
    }

    public static Node right(Node... nodes) {
        var box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    public static <T extends Region> T grow(T node) {
        HBox.setHgrow(node, Priority.ALWAYS);
        return node;
    }

    /** Caixa colorida (fundo, borda e raio) — usada para avisos e resumos. */
    public static <T extends Region> T boxed(T node, String background, String border, double radius, String padding) {
        node.setStyle("-fx-background-color: " + background + "; -fx-background-radius: " + radius + ";"
                + (border == null ? "" : " -fx-border-color: " + border + "; -fx-border-width: 1; -fx-border-radius: " + radius + ";")
                + " -fx-padding: " + padding + ";");
        return node;
    }

    public static Label colored(Label label, String color, boolean bold) {
        label.setStyle("-fx-text-fill: " + color + ";" + (bold ? " -fx-font-weight: 600;" : ""));
        return label;
    }
}
