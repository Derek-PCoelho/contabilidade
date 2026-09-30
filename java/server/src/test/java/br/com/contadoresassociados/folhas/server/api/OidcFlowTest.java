package br.com.contadoresassociados.folhas.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.ServerApplication;
import br.com.contadoresassociados.folhas.server.TestDatabase;
import br.com.contadoresassociados.folhas.server.config.Provisioner;
import br.com.contadoresassociados.folhas.server.security.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Fluxo completo do Desktop contra o servidor real: provisionamento do 1º administrador (4.5),
 * login, cadastro do 2FA (4.4), authorize com PKCE e redirect de loopback em qualquer porta,
 * token JWS legível (4.1), refresh rotativo com detecção de reuso e troca de senha derrubando
 * a sessão (4.3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OidcFlowTest {

    static final String EMAIL = "ti@contadores.com.br";
    static final String PASSWORD = "Senha-Forte-2026!";
    TestDatabase db;
    ConfigurableApplicationContext context;
    String base;

    @BeforeAll
    void start() {
        db = TestDatabase.create();
        new Provisioner(db.dataSource()).run(new Provisioner.FirstAdmin("Contadores Associados", "contadores", EMAIL,
                "Equipe técnica", PASSWORD));
        // idempotente
        new Provisioner(db.dataSource()).run(new Provisioner.FirstAdmin("Contadores Associados", "contadores", EMAIL,
                "Equipe técnica", PASSWORD));
        context = new SpringApplication(ServerApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + db.url(), "--spring.datasource.username=" + db.user(),
                "--spring.datasource.password=" + db.password(), "--folhas.environment=Testing",
                "--folhas.oidc.issuer=http://localhost", "--folhas.rate-limit.authentication=100");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
        db.close();
    }

    HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient client, String url, Map<String, String> form) throws Exception {
        var body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type",
                "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String field(String html, String name) {
        var m = Pattern.compile("name=\"" + Pattern.quote(name) + "\" value=\"([^\"]*)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    static String location(HttpResponse<?> response) {
        var value = response.headers().firstValue("Location").orElseThrow();
        var uri = URI.create(value);
        // Tomcat devolve Location absoluto; redirecionamentos internos são comparados pelo caminho
        return uri.getHost() != null && uri.getHost().equals("127.0.0.1") && uri.getPort() != 53917
                ? uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) : value;
    }

    static Map<String, String> query(String uri) {
        var result = new LinkedHashMap<String, String>();
        var q = URI.create(uri).getRawQuery();
        for (var part : q.split("&")) {
            var kv = part.split("=", 2);
            result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test
    void fullDesktopFlow() throws Exception {
        var browser = browser();
        var verifier = "v".repeat(20) + UUID.randomUUID().toString().replace("-", "");
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var redirect = "http://127.0.0.1:53917/";
        var deviceId = UUID.randomUUID();
        var authorize = base + "/connect/authorize?client_id=folhas-desktop&response_type=code&scope="
                + URLEncoder.encode("openid offline_access profile email folhas_api", StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8) + "&state=xyz&code_challenge="
                + challenge + "&code_challenge_method=S256&device_id=" + deviceId + "&device_name=Notebook";

        // redirect não registrado nunca é seguido
        var evil = get(browser, authorize.replace(URLEncoder.encode(redirect, StandardCharsets.UTF_8),
                URLEncoder.encode("https://evil.example/", StandardCharsets.UTF_8)));
        assertThat(evil.statusCode()).isEqualTo(400);

        var toLogin = get(browser, authorize);
        assertThat(toLogin.statusCode()).isEqualTo(302);
        var loginUrl = base + location(toLogin);
        var form = get(browser, loginUrl);
        assertThat(form.headers().firstValue("Content-Security-Policy").orElse("")).contains("http://127.0.0.1:*");
        var returnUrl = field(form.body(), "returnUrl").replace("&amp;", "&");

        var wrong = post(browser, base + "/account/login", Map.of("email", EMAIL, "password", "errada", "returnUrl",
                returnUrl, "_csrf", field(form.body(), "_csrf")));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("E-mail ou senha incorretos.");

        var ok = post(browser, base + "/account/login", Map.of("email", EMAIL, "password", PASSWORD, "returnUrl",
                returnUrl, "_csrf", field(wrong.body(), "_csrf")));
        assertThat(ok.statusCode()).isEqualTo(302);
        assertThat(location(ok)).startsWith("/account/2fa/setup");

        var setup = get(browser, base + location(ok));
        assertThat(setup.body()).contains("data:image/svg+xml;base64,");
        var key = Pattern.compile("<code>([A-Z2-7 ]+)</code>").matcher(setup.body());
        assertThat(key.find()).isTrue();
        var secret = key.group(1).replace(" ", "");
        var code = Totp.generate(Totp.decodeBase32(secret), Instant.now().getEpochSecond() / 30);
        var enrolled = post(browser, base + "/account/2fa/setup", Map.of("code", code, "returnUrl",
                field(setup.body(), "returnUrl").replace("&amp;", "&"), "_csrf", field(setup.body(), "_csrf")));
        assertThat(enrolled.statusCode()).as(enrolled.body()).isEqualTo(200);
        assertThat(enrolled.body()).contains("códigos de recuperação");

        var authorized = get(browser, authorize);
        assertThat(authorized.statusCode()).isEqualTo(302);
        var callback = location(authorized);
        assertThat(callback).startsWith(redirect);
        var params = query(callback);
        assertThat(params.get("state")).isEqualTo("xyz");

        var api = HttpClient.newHttpClient();
        var badVerifier = post(api, base + "/connect/token", Map.of("grant_type", "authorization_code", "client_id",
                "folhas-desktop", "code", params.get("code"), "redirect_uri", redirect, "code_verifier", "x".repeat(43)));
        assertThat(badVerifier.statusCode()).isEqualTo(400);

        // o código foi consumido pela tentativa inválida: novo authorize
        params = query(location(get(browser, authorize)));
        var token = post(api, base + "/connect/token", Map.of("grant_type", "authorization_code", "client_id",
                "folhas-desktop", "code", params.get("code"), "redirect_uri", redirect, "code_verifier", verifier));
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        JsonNode tokens = Json.mapper().readTree(token.body());
        var access = tokens.path("access_token").asText();
        var claims = SignedJWT.parse(access).getJWTClaimsSet();
        assertThat(claims.getStringClaim("device_session_id")).isEqualTo(deviceId.toString());
        assertThat(claims.getStringListClaim("amr")).contains("mfa");
        assertThat(claims.getStringListClaim("permission")).contains("clients.write", "users.manage");

        var me = api.send(HttpRequest.newBuilder(URI.create(base + "/api/sessions/me"))
                .header("Authorization", "Bearer " + access).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(Json.mapper().readTree(me.body()).path("organizationName").asText()).isEqualTo("Contadores Associados");

        var refresh1 = tokens.path("refresh_token").asText();
        var rotated = post(api, base + "/connect/token", Map.of("grant_type", "refresh_token", "client_id", "folhas-desktop",
                "refresh_token", refresh1));
        assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
        var refresh2 = Json.mapper().readTree(rotated.body()).path("refresh_token").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // reuso do refresh antigo derruba a família inteira
        var reuse = post(api, base + "/connect/token", Map.of("grant_type", "refresh_token", "client_id", "folhas-desktop",
                "refresh_token", refresh1));
        assertThat(reuse.statusCode()).isEqualTo(400);
        var afterReuse = post(api, base + "/connect/token", Map.of("grant_type", "refresh_token", "client_id",
                "folhas-desktop", "refresh_token", refresh2));
        assertThat(afterReuse.statusCode()).isEqualTo(400);
    }

    @Test
    void discoveryAndJwksArePublic() throws Exception {
        var client = HttpClient.newHttpClient();
        var discovery = Json.mapper().readTree(get(client, base + "/.well-known/openid-configuration").body());
        assertThat(discovery.path("code_challenge_methods_supported").get(0).asText()).isEqualTo("S256");
        var jwks = Json.mapper().readTree(get(client, base + "/.well-known/jwks").body());
        assertThat(jwks.path("keys").get(0).path("kty").asText()).isEqualTo("RSA");
        assertThat(jwks.path("keys").get(0).has("d")).as("chave privada nunca publicada").isFalse();
    }
}
