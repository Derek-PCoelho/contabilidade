package br.com.contadoresassociados.folhas.desktop.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Ícones Material (24×24) idênticos aos {@code PathIcon} de MainWindow.axaml. */
public final class Icons {

    public static final String HOME = "M10,20V14H14V20H19V12H22L12,3L2,12H5V20H10Z";
    public static final String CLIENTS = "M12,12C14.21,12 16,10.21 16,8C16,5.79 14.21,4 12,4C9.79,4 8,5.79 8,8C8,10.21 9.79,12 12,12M12,14C7.58,14 4,15.79 4,18V20H20V18C20,15.79 16.42,14 12,14Z";
    public static final String DOCUMENTS = "M14,2H6C4.9,2 4,2.9 4,4V20C4,21.1 4.9,22 6,22H18C19.1,22 20,21.1 20,20V8L14,2M13,9V3.5L18.5,9H13M8,13H16V15H8V13M8,17H16V19H8V17Z";
    public static final String DISPATCH = "M20,8L12,13L4,8V6L12,11L20,6M20,4H4C2.89,4 2,4.89 2,6V18C2,19.1 2.9,20 4,20H20C21.1,20 22,19.1 22,18V6C22,4.89 21.1,4 20,4Z";
    public static final String REPORTS = "M3,3V21H21V3H3M8,19H5V10H8V19M13,19H10V5H13V19M19,19H15V13H19V19Z";
    public static final String HISTORY = "M13.5,8H12V13L16.28,15.54L17,14.33L13.5,12.25V8M13,3C8.03,3 4,7.03 4,12H1L5,16L9,12H6C6,8.13 9.13,5 13,5C16.87,5 20,8.13 20,12C20,15.87 16.87,19 13,19C11.07,19 9.32,18.22 8.05,16.95L6.63,18.37C8.27,20 10.5,21 13,21C17.97,21 22,16.97 22,12C22,7.03 17.97,3 13,3Z";
    public static final String SETTINGS = "M12,15.5C10.07,15.5 8.5,13.93 8.5,12C8.5,10.07 10.07,8.5 12,8.5C13.93,8.5 15.5,10.07 15.5,12C15.5,13.93 13.93,15.5 12,15.5M19.43,12.97C19.47,12.65 19.5,12.33 19.5,12C19.5,11.67 19.47,11.34 19.42,11L21.54,9.37L19.54,5.9L17.05,6.9C16.54,6.5 16,6.16 15.38,5.92L15,3.27H11L10.62,5.92C10,6.16 9.46,6.5 8.95,6.9L6.46,5.9L4.46,9.37L6.58,11C6.53,11.34 6.5,11.67 6.5,12C6.5,12.33 6.53,12.65 6.58,12.97L4.46,14.63L6.46,18.1L8.95,17.1C9.46,17.5 10,17.84 10.62,18.08L11,20.73H15L15.38,18.08C16,17.84 16.54,17.5 17.05,17.1L19.54,18.1L21.54,14.63L19.43,12.97Z";
    public static final String ALERT = "M12,2C6.48,2 2,6.48 2,12C2,17.52 6.48,22 12,22C17.52,22 22,17.52 22,12C22,6.48 17.52,2 12,2M11,7H13V13H11V7M11,15H13V17H11V15Z";

    private Icons() {
    }

    /** Ícone escalado para {@code size} px, com a cor indicada. */
    public static Node icon(String data, double size, String color) {
        var path = new SVGPath();
        path.setContent(data);
        path.setFill(Color.web(color));
        path.getStyleClass().add("nav-icon");
        var scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        return new Group(path);
    }
}
