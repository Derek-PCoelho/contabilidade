package br.com.contadoresassociados.folhas.server.identity;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.db.Sql;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Clientes OIDC registrados em {@code oidc_applications} (mesmo formato JSON do OpenIddict). */
public final class OidcClients {

    public static final String DESKTOP_CLIENT_ID = "folhas-desktop";

    public record OidcClient(UUID id, String clientId, String clientType, List<String> redirectUris,
            List<String> postLogoutRedirectUris, List<String> permissions, List<String> requirements) {

        public boolean requiresPkce() {
            return requirements.contains("ft:pkce") || "public".equals(clientType);
        }

        /**
         * Confere o redirect. Para URIs de loopback registradas ({@code http://localhost/} ou
         * {@code http://127.0.0.1/}) qualquer porta é aceita, conforme RFC 8252 §7.3; o caminho
         * precisa coincidir. Demais URIs exigem igualdade exata.
         */
        public boolean acceptsRedirect(String candidate) {
            if (candidate == null || candidate.isBlank() || candidate.length() > 2000) {
                return false;
            }
            URI uri;
            try {
                uri = URI.create(candidate);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (uri.getFragment() != null) {
                return false;
            }
            for (var registered : redirectUris) {
                if (registered.equals(candidate)) {
                    return true;
                }
                var r = URI.create(registered);
                if ("http".equals(r.getScheme()) && "http".equals(uri.getScheme()) && isLoopback(r.getHost())
                        && isLoopback(uri.getHost()) && samePath(r.getPath(), uri.getPath())) {
                    return true;
                }
            }
            return false;
        }

        public boolean acceptsPostLogout(String candidate) {
            return candidate != null && (postLogoutRedirectUris.contains(candidate)
                    || acceptsRedirectList(postLogoutRedirectUris, candidate));
        }

        private static boolean acceptsRedirectList(List<String> list, String candidate) {
            return new OidcClient(null, null, null, list, List.of(), List.of(), List.of()).acceptsRedirect(candidate);
        }

        private static boolean isLoopback(String host) {
            if (host == null) {
                return false;
            }
            var h = host.toLowerCase(Locale.ROOT);
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]");
        }

        private static boolean samePath(String a, String b) {
            var x = a == null || a.isEmpty() ? "/" : a;
            var y = b == null || b.isEmpty() ? "/" : b;
            return x.equals(y);
        }
    }

    public Optional<OidcClient> find(Connection c, String clientId) throws SQLException {
        if (clientId == null || clientId.isBlank() || clientId.length() > 100) {
            return Optional.empty();
        }
        try (var st = c.prepareStatement("SELECT \"Id\", \"ClientId\", \"ClientType\", \"RedirectUris\", "
                + "\"PostLogoutRedirectUris\", \"Permissions\", \"Requirements\" FROM oidc_applications WHERE \"ClientId\" = ?")) {
            st.setString(1, clientId);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OidcClient(Sql.uuid(rs, "Id"), rs.getString("ClientId"), rs.getString("ClientType"),
                        list(rs.getString("RedirectUris")), list(rs.getString("PostLogoutRedirectUris")),
                        list(rs.getString("Permissions")), list(rs.getString("Requirements"))));
            }
        }
    }

    public void ensureDesktopClient(Connection c) throws SQLException {
        if (find(c, DESKTOP_CLIENT_ID).isPresent()) {
            return;
        }
        try (var st = c.prepareStatement("INSERT INTO oidc_applications (\"Id\", \"ApplicationType\", \"ClientId\", "
                + "\"ClientType\", \"ConcurrencyToken\", \"ConsentType\", \"DisplayName\", \"Permissions\", "
                + "\"PostLogoutRedirectUris\", \"RedirectUris\", \"Requirements\") VALUES (?, 'native', ?, 'public', ?, "
                + "'implicit', 'Folhas da Michelly Desktop', ?, ?, ?, ?)")) {
            st.setObject(1, UUID.randomUUID());
            st.setString(2, DESKTOP_CLIENT_ID);
            st.setString(3, UUID.randomUUID().toString());
            st.setString(4, Json.write(List.of("ept:authorization", "ept:end_session", "ept:revocation", "ept:token",
                    "gt:authorization_code", "gt:refresh_token", "rst:code", "scp:email", "scp:profile", "scp:roles",
                    "scp:folhas_api")));
            st.setString(5, Json.write(List.of("http://localhost/", "com.danziatus.folhasdamichelly:/signout-callback")));
            st.setString(6, Json.write(List.of("http://localhost/", "com.danziatus.folhasdamichelly:/callback")));
            st.setString(7, Json.write(List.of("ft:pkce")));
            st.executeUpdate();
        }
    }

    private static List<String> list(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(json, new TypeReference<List<String>>() { });
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
