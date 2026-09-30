package br.com.contadoresassociados.folhas.server.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Páginas HTML mínimas do login (pt-BR, sem scripts; CSP da {@code EdgeFilter}). */
final class Pages {

    private static final String STYLE = "body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#f4f6f8;"
            + "color:#1f2933;margin:0}main{max-width:420px;margin:8vh auto;background:#fff;border-radius:12px;"
            + "box-shadow:0 8px 24px rgba(0,0,0,.08);padding:32px}h1{font-size:22px;margin:0 0 4px}p{line-height:1.5}"
            + "label{display:block;margin:16px 0 4px;font-weight:600}input{width:100%;box-sizing:border-box;padding:10px;"
            + "border:1px solid #cbd2d9;border-radius:8px;font-size:15px}button{margin-top:24px;width:100%;padding:12px;"
            + "border:0;border-radius:8px;background:#2f6f4e;color:#fff;font-size:15px;font-weight:600;cursor:pointer}"
            + "a.button{display:block;text-align:center;text-decoration:none;margin-top:24px;padding:12px;border-radius:8px;"
            + "background:#2f6f4e;color:#fff;font-weight:600}"
            + ".error{background:#fde8e8;color:#9b1c1c;padding:10px 12px;border-radius:8px;margin-top:16px}"
            + ".muted{color:#616e7c;font-size:14px}code{background:#eef2f5;padding:2px 6px;border-radius:4px;"
            + "word-break:break-all}ul.codes{columns:2;font-family:monospace;font-size:15px}img{display:block;margin:16px auto}";

    private Pages() {
    }

    static void render(HttpServletResponse response, int status, String title, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        var html = "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + escape(title)
                + " — Folhas da Michelly</title><style>" + STYLE + "</style></head><body><main><h1>Folhas da Michelly</h1>"
                + body + "</main></body></html>";
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    static String error(String message) {
        return message == null ? "" : "<div class=\"error\" role=\"alert\">" + escape(message) + "</div>";
    }

    static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\"" + escape(value == null ? "" : value) + "\">";
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (var ch : value.toCharArray()) {
            switch (ch) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
