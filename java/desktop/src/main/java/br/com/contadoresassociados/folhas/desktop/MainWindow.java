package br.com.contadoresassociados.folhas.desktop;

import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.desktop.sections.Section;
import br.com.contadoresassociados.folhas.desktop.state.AppSection;
import br.com.contadoresassociados.folhas.desktop.state.OperationalPeriod;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.Icons;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Janela principal: barra lateral (204 px), cabeçalho (108 px), conteúdo da seção e barra de
 * status (64 px) — mesma grade de {@code MainWindow.axaml}.
 */
public final class MainWindow extends BorderPane {

    private final ShellState state;
    private final Map<AppSection, Section> sections = new EnumMap<>(AppSection.class);

    public MainWindow(ShellState state, Function<AppSection, Section> factory) {
        this.state = state;
        getStyleClass().add("main-window");
        setLeft(sidebar());
        var content = new StackPane();
        for (var section : AppSection.values()) {
            var view = factory.apply(section);
            sections.put(section, view);
            var node = view.node();
            visibleWhen(node, state.is(section));
            content.getChildren().add(node);
        }
        var center = new BorderPane(content);
        center.setTop(topbar());
        center.setBottom(statusbar());
        setCenter(center);
        state.section().addListener((obs, old, now) -> sections.get(now).onShown());
        sections.get(state.section().get()).onShown();
    }

    public Section section(AppSection section) {
        return sections.get(section);
    }

    // ---------------------------------------------------------------- barra lateral

    private Node sidebar() {
        var avatar = new StackPane(text("M", "brand-avatar-letter"), image("logo1.png", 48, 48, true));
        avatar.getStyleClass().add("brand-avatar");
        avatar.setMinSize(48, 48);
        avatar.setMaxSize(48, 48);
        avatar.setClip(new Circle(24, 24, 24));
        var brandText = new VBox(2, text("Folhas da", "brand-small"), text("Michelly", "brand-large"));
        brandText.setAlignment(Pos.CENTER_LEFT);
        var brand = row(10, avatar, brandText);

        var menu = new VBox(2, text("MENU", "menu-caption"),
                nav(AppSection.HOME, "Início", Icons.HOME),
                nav(AppSection.CLIENTS, "Clientes", Icons.CLIENTS),
                nav(AppSection.DOCUMENTS, "Documentos", Icons.DOCUMENTS),
                nav(AppSection.DISPATCH, "Envios", Icons.DISPATCH),
                nav(AppSection.REPORTS, "Relatórios", Icons.REPORTS),
                nav(AppSection.HISTORY, "Histórico", Icons.HISTORY),
                nav(AppSection.SETTINGS, "Configurações", Icons.SETTINGS));
        VBox.setMargin(menu, new Insets(24, 0, 0, 0));

        var officeLogo = new StackPane(text("60", "office-logo-text"), image("aaa2.png", 48, 44, false));
        officeLogo.getStyleClass().add("office-logo");
        officeLogo.setMinSize(48, 44);
        officeLogo.setMaxSize(48, 44);
        var clip = new Rectangle(48, 44);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        officeLogo.setClip(clip);
        var officeName = new VBox(text("Contadores", "office-name"), text("Associados", "office-sub"));
        officeName.setAlignment(Pos.CENTER_LEFT);
        var office = row(10, officeLogo, officeName);
        office.getStyleClass().add("office-badge");

        var filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);
        var sidebar = new VBox(brand, menu, filler, office);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(204);
        sidebar.setMinWidth(204);
        return sidebar;
    }

    private Node nav(AppSection section, String label, String icon) {
        var marker = new Region();
        marker.getStyleClass().add("nav-marker");
        Runnable refresh = () -> {
            marker.getStyleClass().remove("current");
            if (state.section().get() == section) {
                marker.getStyleClass().add("current");
            }
        };
        state.section().addListener((obs, old, now) -> refresh.run());
        refresh.run();
        var iconBox = new StackPane(Icons.icon(icon, 18, "#E8E3D8"));
        iconBox.setMinWidth(26);
        iconBox.setPrefWidth(26);
        var button = new Button(label, iconBox);
        button.getStyleClass().add("nav");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> state.show(section));
        button.setAccessibleText(label);
        HBox.setHgrow(button, Priority.ALWAYS);
        var row = new HBox(marker, button);
        row.setFillHeight(true);
        return row;
    }

    // ---------------------------------------------------------------- cabeçalho

    private Node topbar() {
        var title = h1(state.sectionTitle());
        title.setAccessibleRoleDescription("heading");
        var titleBox = new VBox(3, title, muted(state.sectionDescription()));
        titleBox.setMaxWidth(470);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        var marker = new Region();
        marker.getStyleClass().add("period-marker");
        var periodValue = text(state.workPeriodLabel(), "period-value");
        periodValue.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        var periodText = new VBox(3, text("COMPETÊNCIA", "period-caption"), periodValue);
        periodText.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(periodText, Priority.ALWAYS);
        var periodTextRow = new HBox(12, marker, periodText);

        var year = new ComboBox<>(state.years());
        year.valueProperty().bindBidirectional(state.selectedYear());
        year.getStyleClass().add("compact");
        year.setPrefWidth(104);
        year.setAccessibleText("Ano da competência");
        var month = new ComboBox<>(state.months());
        month.valueProperty().bindBidirectional(state.selectedMonth());
        month.getStyleClass().add("compact");
        month.setPrefWidth(118);
        month.setAccessibleText("Mês da competência");
        var yearBox = new VBox(3, text("ANO", "period-small"), year);
        var monthBox = new VBox(3, text("MÊS", "period-small"), month);
        var period = new HBox(10, periodTextRow, yearBox, monthBox);
        HBox.setHgrow(periodTextRow, Priority.ALWAYS);
        period.getStyleClass().add("period-box");
        period.setPrefWidth(400);
        period.setMaxWidth(400);
        period.setMinWidth(400);
        period.setAlignment(Pos.CENTER_LEFT);

        var bar = new HBox(14, titleBox, spacer(), period);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        bar.setMinHeight(108);
        bar.setPrefHeight(108);
        return bar;
    }

    // ---------------------------------------------------------------- barra de status

    private Node statusbar() {
        var message = text(state.statusMessage(), "status-text");
        message.setWrapText(true);
        message.setAccessibleText("Estado da operação");
        var bar = new HBox(10, text("●", "status-dot"), message);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("statusbar");
        bar.setMinHeight(64);
        bar.setPrefHeight(64);
        return bar;
    }

    /** Imagem da marca; {@code cover} recorta ao centro (equivalente a {@code UniformToFill}). */
    public static Node image(String name, double width, double height, boolean cover) {
        var stream = MainWindow.class.getResourceAsStream("branding/" + name);
        if (stream == null) {
            return new Label();
        }
        var image = new Image(stream);
        var view = new ImageView(image);
        view.setSmooth(true);
        view.setFitWidth(width);
        view.setFitHeight(height);
        if (cover && image.getWidth() > 0 && image.getHeight() > 0) {
            var scale = Math.max(width / image.getWidth(), height / image.getHeight());
            var w = width / scale;
            var h = height / scale;
            view.setViewport(new javafx.geometry.Rectangle2D(
                    (image.getWidth() - w) / 2, (image.getHeight() - h) / 2, w, h));
            view.setPreserveRatio(false);
        } else {
            view.setPreserveRatio(true);
        }
        return view;
    }
}
