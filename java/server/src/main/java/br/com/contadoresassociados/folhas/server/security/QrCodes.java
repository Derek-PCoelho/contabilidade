package br.com.contadoresassociados.folhas.server.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** QR code em SVG ({@code data:} URI), sem scripts nem recursos externos. */
public final class QrCodes {

    private QrCodes() {
    }

    public static String svgDataUri(String text) {
        try {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            var size = matrix.getWidth();
            var path = new StringBuilder();
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    if (matrix.get(x, y)) {
                        path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                    }
                }
            }
            var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                    + "\" width=\"220\" height=\"220\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" "
                    + "fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
            return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        } catch (WriterException e) {
            throw new IllegalStateException("Não foi possível gerar o QR code.", e);
        }
    }
}
