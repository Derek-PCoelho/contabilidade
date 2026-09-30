package br.com.contadoresassociados.folhas.desktop;

import javafx.application.Application;

/**
 * Ponto de entrada. Classe separada de {@link FolhasApp} para o executável funcionar sem
 * module-path (o JavaFX exige isso quando a classe principal estende {@code Application}).
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        var guard = SingleInstanceGuard.tryAcquire(DesktopEnvironment.dataDirectory());
        if (guard == null) {
            System.err.println("Folhas da Michelly já está aberto nesta conta de usuário.");
            return;
        }
        try (guard) {
            Application.launch(FolhasApp.class, args);
        }
    }
}
