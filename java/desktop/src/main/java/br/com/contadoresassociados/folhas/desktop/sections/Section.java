package br.com.contadoresassociados.folhas.desktop.sections;

import javafx.scene.Node;

/** Uma seção da janela principal (Início, Clientes, Documentos, ...). */
public interface Section {

    Node node();

    /** Chamado quando a seção passa a ser exibida (recarrega dados sob demanda). */
    default void onShown() {
    }
}
