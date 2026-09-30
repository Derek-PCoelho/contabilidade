package br.com.contadoresassociados.folhas.desktop.sections;

import static br.com.contadoresassociados.folhas.desktop.ui.Ui.*;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

/** Seção ainda em migração — exibida somente durante o desenvolvimento incremental. */
public final class PendingSection implements Section {

    private final Node root;

    public PendingSection(String title, String description) {
        root = scroll(new VBox(14, card(8, eyebrow("EM MIGRAÇÃO"), h2(title), muted(description))), 28);
    }

    @Override
    public Node node() {
        return root;
    }
}
