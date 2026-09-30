package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import br.com.contadoresassociados.folhas.desktop.MainWindow;
import br.com.contadoresassociados.folhas.desktop.state.AppSection;
import br.com.contadoresassociados.folhas.desktop.state.ShellState;
import br.com.contadoresassociados.folhas.desktop.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/** Visão geral — aviso de e-mail, destaque, indicadores, fluxo de trabalho e atalhos. */
public final class HomeSection implements Section {

    private final Node root;

    public HomeSection(ShellState state, Runnable newClient) {
        var notice = new HBox(11,
                Icons.icon(Icons.ALERT, 20, "#A47213"),
                growing(stack(0,
                        text(state.emailSetupNoticeTitle(), "notice-title"),
                        muted(state.emailSetupNoticeText()))),
                button("Ir para conexão", "ghost", () -> state.show(AppSection.SETTINGS)));
        notice.setAlignment(Pos.CENTER_LEFT);
        notice.getStyleClass().add("notice");
        visibleWhen(notice, state.showEmailSetupNotice());

        var heroTitle = wrap(text("Tudo o que precisa, na ordem certa.", "hero-title"));
        var heroText = wrap(text("Cadastre o cliente, importe os documentos, confira as pendências e só então prepare as mensagens.", "hero-text"));
        heroText.setMaxWidth(760);
        var heroButtons = row(10,
                button("Importar documentos", "primary", () -> state.show(AppSection.DOCUMENTS)),
                button("Cadastrar cliente", "ghost", newClient));
        heroButtons.setStyle("-fx-padding: 8 0 0 0;");
        var heroLeft = new VBox(10, text("BOM TRABALHO, MICHELLY", "hero-eyebrow"), heroTitle, heroText, heroButtons);
        heroLeft.setAlignment(Pos.CENTER_LEFT);
        var photo = new StackPane(text("Nosso escritório", "hero-photo-caption"), MainWindow.image("aaa1.png", 220, 128, true));
        photo.getStyleClass().add("hero-image");
        photo.setMinSize(220, 128);
        photo.setMaxSize(220, 128);
        var clip = new Rectangle(220, 128);
        clip.setArcWidth(26);
        clip.setArcHeight(26);
        photo.setClip(clip);
        var hero = new HBox(28, heroLeft, photo);
        HBox.setHgrow(heroLeft, Priority.ALWAYS);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("hero");

        var metrics = columns(14, 0, 1, 1, 1);
        metrics.add(metric("DOCUMENTOS", state.visibleDocumentCount().asString(), "#28251F",
                "documentos na competência selecionada"), 0, 0);
        metrics.add(metric("PRECISAM DE ATENÇÃO", state.blockedDocumentCount().asString(), "#A14C3A",
                "bloqueados ou repetidos"), 1, 0);
        metrics.add(metric("APROVADOS", state.approvedGroupCount().asString(), "#477945",
                "conjuntos liberados para mensagem"), 2, 0);

        var steps = columns(10, 0, 1, 1, 1, 1);
        var labels = new String[] {"Cadastrar cliente", "Conferir documentos", "Preparar mensagens", "Concluir e relatar"};
        for (var i = 0; i < labels.length; i++) {
            var circle = new StackPane(text(Integer.toString(i + 1), "strong"));
            circle.getStyleClass().add("step-circle");
            var caption = wrap(text(labels[i], "step-caption"));
            caption.setAlignment(Pos.CENTER);
            caption.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            var step = new VBox(6, circle, caption);
            step.setAlignment(Pos.TOP_CENTER);
            steps.add(step, i, 0);
        }
        var workflow = card(12, h2("Seu fluxo de trabalho"), steps,
                muted("O aplicativo preserva as travas de segurança nos bastidores e organiza cada decisão na ordem certa."));
        var shortcuts = card(9, h2("Atalhos"),
                stretch(button("Consultar clientes cadastrados", "ghost", () -> state.show(AppSection.CLIENTS))),
                stretch(button("Revisar documentos", "ghost", () -> state.show(AppSection.DOCUMENTS))),
                stretch(button("Preparar mensagens", "ghost", () -> state.show(AppSection.DISPATCH))));
        var lower = columns(14, 0, 2, 1);
        lower.add(workflow, 0, 0);
        lower.add(shortcuts, 1, 0);
        GridPane.setValignment(shortcuts, javafx.geometry.VPos.TOP);

        root = scroll(new VBox(18, notice, hero, metrics, lower), 28);
        root.setAccessibleText("Conteúdo inicial");
    }

    private static Node metric(String eyebrow, javafx.beans.value.ObservableValue<String> value, String color,
            String caption) {
        var number = new Label();
        number.textProperty().bind(value);
        number.getStyleClass().add("big-number");
        number.setStyle("-fx-text-fill: " + color + ";");
        var box = card(7, eyebrow(eyebrow), number, muted(caption));
        box.setMaxHeight(Double.MAX_VALUE);
        return box;
    }

    private static Node growing(VBox box) {
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    @Override
    public Node node() {
        return root;
    }
}
