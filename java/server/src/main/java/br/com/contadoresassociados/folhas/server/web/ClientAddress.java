package br.com.contadoresassociados.folhas.server.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Endereço real do cliente (pendência 4.11). {@code X-Forwarded-For}/{@code -Proto} só são
 * aceitos quando a conexão vem de um proxy listado em {@code folhas.forwarded-headers.known-proxies};
 * caso contrário qualquer cliente poderia forjar o IP e fugir do limite de tentativas de login.
 */
public final class ClientAddress {

    public static final String ATTRIBUTE = "folhas.clientAddress";
    public static final String SCHEME_ATTRIBUTE = "folhas.clientScheme";

    private ClientAddress() {
    }

    public static void resolve(HttpServletRequest request, List<String> knownProxies) {
        var remote = request.getRemoteAddr();
        var address = remote;
        var scheme = request.getScheme();
        if (knownProxies.contains(remote)) {
            var forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // o último salto não confiável é o cliente; percorre da direita para a esquerda
                var hops = forwarded.split(",");
                for (var i = hops.length - 1; i >= 0; i--) {
                    var hop = hops[i].strip();
                    if (!hop.isEmpty()) {
                        address = hop;
                        if (!knownProxies.contains(hop)) {
                            break;
                        }
                    }
                }
            }
            var proto = request.getHeader("X-Forwarded-Proto");
            if ("https".equalsIgnoreCase(proto) || "http".equalsIgnoreCase(proto)) {
                scheme = proto.toLowerCase(java.util.Locale.ROOT);
            }
        }
        request.setAttribute(ATTRIBUTE, address);
        request.setAttribute(SCHEME_ATTRIBUTE, scheme);
    }

    public static String of(HttpServletRequest request) {
        var value = request.getAttribute(ATTRIBUTE);
        return value instanceof String s ? s : request.getRemoteAddr();
    }

    public static boolean secure(HttpServletRequest request) {
        var value = request.getAttribute(SCHEME_ATTRIBUTE);
        return "https".equals(value instanceof String s ? s : request.getScheme());
    }

    public static boolean isLoopback(HttpServletRequest request) {
        var remote = request.getRemoteAddr();
        return "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
    }
}
