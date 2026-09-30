package br.com.contadoresassociados.folhas.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import br.com.contadoresassociados.folhas.infrastructure.remote.CentralApiClient;
import br.com.contadoresassociados.folhas.infrastructure.remote.DesktopOidcClient;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import br.com.contadoresassociados.folhas.server.ServerApplication;
import br.com.contadoresassociados.folhas.server.TestDatabase;
import br.com.contadoresassociados.folhas.server.config.Provisioner;
import br.com.contadoresassociados.folhas.server.security.Totp;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Login do Desktop (pendência 6.1) com o {@link DesktopOidcClient} real contra o servidor real:
 * o "navegador" percorre login + 2FA e é redirecionado ao loopback aberto pelo cliente.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopLoginTest {

    static final String EMAIL = "michelly@contadores.com.br";
    static final String PASSWORD = "Senha-Forte-Desktop-2026!";
    TestDatabase db;
    ConfigurableApplicationContext context;
    String base;

    @BeforeAll
    void start() {
        db = TestDatabase.create();
        new Provisioner(db.dataSource()).run(new Provisioner.FirstAdmin("Contadores Associados", "contadores", EMAIL,
                "Michelly", PASSWORD));
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        base = "http://127.0.0.1:" + port;
        context = new SpringApplication(ServerApplication.class).run("--server.port=" + port, "--folhas.oidc.issuer=" + base,
                "--spring.datasource.url=" + db.url(), "--spring.datasource.username=" + db.user(),
                "--spring.datasource.password=" + db.password(), "--folhas.environment=Testing",
                "--folhas.rate-limit.authentication=100");
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
        db.close();
    }

    /** Navegador simulado: login com senha, cadastro do 2FA e retorno ao loopback. */
    void browse(URI authorize) {
        try {
            var browser = OidcFlowTest.class.getDeclaredConstructor().newInstance().browser();
            var toLogin = OidcFlowTest.get(browser, authorize.toString());
            var loginUrl = base + OidcFlowTest.location(toLogin);
            var form = OidcFlowTest.get(browser, loginUrl);
            var ok = OidcFlowTest.post(browser, base + "/account/login", Map.of("email", EMAIL, "password", PASSWORD,
                    "returnUrl", OidcFlowTest.field(form.body(), "returnUrl").replace("&amp;", "&"), "_csrf",
                    OidcFlowTest.field(form.body(), "_csrf")));
            var next = OidcFlowTest.location(ok);
            if (next.startsWith("/account/2fa/setup")) {
                var setup = OidcFlowTest.get(browser, base + next);
                var key = Pattern.compile("<code>([A-Z2-7 ]+)</code>").matcher(setup.body());
                assertThat(key.find()).isTrue();
                var code = Totp.generate(Totp.decodeBase32(key.group(1).replace(" ", "")), Instant.now().getEpochSecond() / 30);
                OidcFlowTest.post(browser, base + "/account/2fa/setup", Map.of("code", code, "returnUrl",
                        OidcFlowTest.field(setup.body(), "returnUrl").replace("&amp;", "&"), "_csrf",
                        OidcFlowTest.field(setup.body(), "_csrf")));
            }
            var authorized = OidcFlowTest.get(browser, authorize.toString());
            var callback = authorized.headers().firstValue("Location").orElseThrow();
            assertThat(callback).startsWith("http://127.0.0.1:");
            // entrega o código ao servidor de loopback do Desktop
            OidcFlowTest.get(browser, callback);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void desktopLogsInRefreshesCallsApiAndLogsOut() {
        var vault = new InMemorySecretStore();
        var opened = new AtomicReference<URI>();
        var oidc = new DesktopOidcClient(URI.create(base), vault, null, uri -> {
            opened.set(uri);
            Thread.ofVirtual().start(() -> browse(uri));
        }, Duration.ofSeconds(30), null);

        assertThat(oidc.current()).isEmpty();
        var session = oidc.login("Notebook da Michelly", CancellationToken.NONE);
        assertThat(opened.get().getQuery()).contains("code_challenge_method=S256").contains("client_id=folhas-desktop")
                .contains("device_name=Notebook+da+Michelly");
        assertThat(session.email()).isEqualTo(EMAIL);
        assertThat(session.permissions()).contains(AppPermission.EMAIL_SEND, AppPermission.DOCUMENTS_PROCESS);
        assertThat(session.multiFactor()).isTrue();
        assertThat(session.scopeKey()).hasSize(32).doesNotContain("-");
        assertThat(vault.retrieve(DesktopOidcClient.REFRESH_TOKEN_KEY)).isPresent();
        assertThat(oidc.current()).contains(session);

        // O cliente da API central usa o token do cofre.
        var api = new CentralApiClient(null, URI.create(base), vault, null, "1.0.0");
        assertThat(api.get("connect/userinfo").status()).isEqualTo(200);

        var refreshed = oidc.refresh();
        assertThat(refreshed.userId()).isEqualTo(session.userId());
        assertThat(refreshed.deviceSessionId()).isEqualTo(session.deviceSessionId());

        var oldRefresh = vault.retrieve(DesktopOidcClient.REFRESH_TOKEN_KEY).orElseThrow();
        oidc.logout();
        assertThat(oidc.current()).isEmpty();
        assertThat(api.get("connect/userinfo").status()).isEqualTo(401);
        // Revogado no servidor: o refresh antigo não serve mais.
        vault.store(DesktopOidcClient.REFRESH_TOKEN_KEY, oldRefresh);
        assertThatThrownBy(oidc::refresh).isInstanceOf(DesktopOidcClient.LoginException.class);
        assertThat(vault.retrieve(DesktopOidcClient.REFRESH_TOKEN_KEY)).isEmpty();
    }
}
