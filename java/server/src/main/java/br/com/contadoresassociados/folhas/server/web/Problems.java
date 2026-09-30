package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Corpo de erro no formato ProblemDetails do ASP.NET, acrescido de {@code code} (4.17). */
public final class Problems {

    private Problems() {
    }

    public static Map<String, Object> body(HttpServletRequest request, int status, String code, String title,
            String detail, List<String> details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        if (detail != null) {
            body.put("detail", detail);
        }
        body.put("code", code);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        body.put("correlationId", RequestContext.correlationId(request));
        return body;
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
            String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getOutputStream().write(Json.writeBytes(body(request, status, code, title, detail, List.of())));
    }

    public static String title(int status) {
        return switch (status) {
            case 400 -> "Dados inválidos";
            case 401 -> "Autenticação necessária";
            case 403 -> "Acesso negado";
            case 404 -> "Não encontrado";
            case 409 -> "Conflito de versão";
            case 413 -> "Conteúdo grande demais";
            case 429 -> "Muitas solicitações";
            case 503 -> "Serviço indisponível";
            default -> status >= 500 ? "Erro interno" : "Solicitação recusada";
        };
    }
}
