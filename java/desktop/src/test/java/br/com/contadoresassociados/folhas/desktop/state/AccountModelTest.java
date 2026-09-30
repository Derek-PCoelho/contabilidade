package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import br.com.contadoresassociados.folhas.infrastructure.remote.DesktopOidcClient;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Conta do escritório: perfil Local sem login e perfil Conectado lendo a sessão do cofre. */
class AccountModelTest {

    private final ShellState shell = new ShellState(2026, 3);

    static String token(UUID org, long exp) {
        var header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        var payload = "{\"sub\":\"" + UUID.randomUUID() + "\",\"organization_id\":\"" + org + "\",\"device_session_id\":\""
                + UUID.randomUUID() + "\",\"email\":\"michelly@contadores.com.br\",\"name\":\"Michelly\",\"role\":[\"Operador\"],"
                + "\"permission\":[\"documents.process\",\"email.send\",\"desconhecida\"],\"amr\":[\"pwd\",\"mfa\"],\"exp\":" + exp + "}";
        return header + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".assinatura";
    }

    @Test
    void localProfileNeedsNoLogin() {
        var model = new AccountModel(null, shell, UiTasks.synchronous(), ZoneId.of("America/Sao_Paulo"));
        assertThat(model.connectedProfile()).isFalse();
        assertThat(model.canSignIn().get()).isFalse();
        assertThat(model.status.get()).startsWith("Perfil local");
    }

    @Test
    void connectedProfileReadsSessionFromVaultAndSignsOutOffline() {
        var vault = new InMemorySecretStore();
        var org = UUID.randomUUID();
        var exp = Instant.now().plusSeconds(3600).getEpochSecond();
        vault.store(DesktopOidcClient.ACCESS_TOKEN_KEY, token(org, exp));
        // Server inexistente: a saída ainda limpa o cofre (a revogação falha em silêncio).
        var oidc = new DesktopOidcClient(URI.create("http://127.0.0.1:9/"), vault, null, uri -> { }, null, null);
        var model = new AccountModel(oidc, shell, UiTasks.synchronous(), ZoneId.of("America/Sao_Paulo"));
        assertThat(model.status.get()).isEqualTo("Nenhuma sessão ativa neste computador.");

        model.refresh();
        var session = model.session.get();
        assertThat(session).isNotNull();
        assertThat(session.scopeKey()).isEqualTo(org.toString().replace("-", ""));
        assertThat(session.permissions()).hasSize(2);
        assertThat(model.status.get()).isEqualTo("Conectado como Michelly (michelly@contadores.com.br).");
        assertThat(model.details.get()).contains("Papéis: Operador").contains("incluindo envio real")
                .contains("verificação em duas etapas confirmada");
        assertThat(model.canSignIn().get()).isFalse();

        model.signOut();
        assertThat(model.session.get()).isNull();
        assertThat(vault.retrieve(DesktopOidcClient.ACCESS_TOKEN_KEY)).isEmpty();
        assertThat(model.canSignIn().get()).isTrue();
    }
}
