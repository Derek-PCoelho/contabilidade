package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.application.common.OperationCancelledException;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import br.com.contadoresassociados.folhas.infrastructure.remote.DesktopOidcClient;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.net.InetAddress;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Conta do escritório no Server central (pendência 6.1): entrar pelo navegador do sistema,
 * ver a sessão, renovar e sair. No perfil Local a seção explica que nada disso é necessário.
 */
public final class AccountModel {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final DesktopOidcClient oidc;
    private final ShellState shell;
    private final UiTasks tasks;
    private final ZoneId zone;
    private Runnable sessionChanged = () -> { };
    private volatile CancellationToken pending;

    public final ObjectProperty<DesktopOidcClient.Session> session = new SimpleObjectProperty<>();
    public final BooleanProperty signingIn = new SimpleBooleanProperty(false);
    public final StringProperty status = new SimpleStringProperty("");
    public final StringProperty details = new SimpleStringProperty("");

    public AccountModel(DesktopOidcClient oidc, ShellState shell, UiTasks tasks, ZoneId zone) {
        this.oidc = oidc;
        this.shell = Objects.requireNonNull(shell);
        this.tasks = Objects.requireNonNull(tasks);
        this.zone = Objects.requireNonNull(zone);
        session.addListener((obs, old, now) -> describe());
        describe();
    }

    public void onSessionChanged(Runnable action) {
        sessionChanged = Objects.requireNonNull(action);
    }

    public boolean connectedProfile() {
        return oidc != null;
    }

    public BooleanBinding isConnectedProfile() {
        return Bindings.createBooleanBinding(this::connectedProfile);
    }

    public BooleanBinding signedIn() {
        return session.isNotNull();
    }

    public BooleanBinding canSignIn() {
        return Bindings.createBooleanBinding(() -> connectedProfile() && session.get() == null && !signingIn.get(), session,
                signingIn);
    }

    public String serverAddress() {
        return oidc == null ? "" : oidc.authority().toString();
    }

    private void describe() {
        if (oidc == null) {
            status.set("Perfil local: este computador trabalha sem Server e sem login.");
            details.set("Clientes, documentos, conferência, simulação e relatórios ficam somente neste computador. "
                    + "O login é necessário apenas quando o escritório usa o Server central.");
            return;
        }
        var s = session.get();
        if (s == null) {
            status.set(signingIn.get() ? "Aguardando o login no navegador…" : "Nenhuma sessão ativa neste computador.");
            details.set("Entre com a conta do escritório para usar os dados compartilhados e as operações que dependem "
                    + "do controle central. Sem login, nenhuma operação é liberada.");
            return;
        }
        var name = s.displayName() == null || s.displayName().isBlank() ? s.email() : s.displayName();
        status.set("Conectado como " + name + (s.email().isBlank() ? "" : " (" + s.email() + ")") + ".");
        var roles = s.roles().isEmpty() ? "sem papel definido" : String.join(", ", s.roles());
        var expires = s.expiresAt() == null ? "" : " Sessão válida até " + s.expiresAt().atZone(zone).format(WHEN) + ".";
        details.set("Papéis: " + roles + " • " + s.permissions().size() + " permissão(ões)"
                + (s.permissions().contains(AppPermission.EMAIL_SEND) ? ", incluindo envio real" : "")
                + " • " + (s.multiFactor() ? "verificação em duas etapas confirmada" : "sem verificação em duas etapas")
                + "." + expires);
    }

    /** Ao abrir o aplicativo ou a seção: lê a sessão do cofre e renova se estiver perto de expirar. */
    public void refresh() {
        if (oidc == null) {
            return;
        }
        tasks.run(oidc::ensureFresh, (Optional<DesktopOidcClient.Session> s) -> {
            var changed = !Objects.equals(session.get(), s.orElse(null));
            session.set(s.orElse(null));
            if (changed) {
                sessionChanged.run();
            }
        }, error -> shell.status(ErrorMessages.friendly(error)));
    }

    public void signIn() {
        if (oidc == null || signingIn.get()) {
            return;
        }
        var token = CancellationToken.create();
        pending = token;
        signingIn.set(true);
        describe();
        shell.status("Abrimos a página de login do escritório no navegador. Conclua por lá e volte ao aplicativo.");
        tasks.run(() -> oidc.login(deviceName(), token), (DesktopOidcClient.Session s) -> {
            signingIn.set(false);
            session.set(s);
            shell.status("Login concluído. " + status.get());
            sessionChanged.run();
        }, error -> {
            signingIn.set(false);
            describe();
            shell.status(switch (error) {
                case OperationCancelledException e -> "Login cancelado. Nenhuma sessão foi criada.";
                case DesktopOidcClient.LoginException e -> "Não foi possível entrar: " + e.getMessage();
                default -> ErrorMessages.friendly(error);
            });
        });
    }

    public void cancelSignIn() {
        var token = pending;
        if (token != null) {
            token.cancel();
        }
    }

    public void renew() {
        if (oidc == null || session.get() == null) {
            return;
        }
        tasks.run(oidc::refresh, (DesktopOidcClient.Session s) -> {
            session.set(s);
            shell.status("Sessão renovada com o Server do escritório.");
        }, error -> {
            session.set(oidc.current().orElse(null));
            shell.status(error instanceof DesktopOidcClient.LoginException e ? e.getMessage() : ErrorMessages.friendly(error));
            sessionChanged.run();
        });
    }

    public void signOut() {
        if (oidc == null) {
            return;
        }
        tasks.run(oidc::logout, () -> {
            session.set(null);
            shell.status("Você saiu da conta. A sessão deste computador foi encerrada no Server.");
            sessionChanged.run();
        }, error -> shell.status(ErrorMessages.friendly(error)));
    }

    static String deviceName() {
        try {
            var host = InetAddress.getLocalHost().getHostName();
            var os = System.getProperty("os.name", "");
            return (host == null || host.isBlank() ? "Computador" : host) + (os.isBlank() ? "" : " (" + os + ")");
        } catch (java.io.IOException e) {
            return "Computador do escritório";
        }
    }

    /** Resumo usado no cabeçalho/status. */
    public String permissionsSummary() {
        var s = session.get();
        return s == null ? "" : s.permissions().stream().map(AppPermission::wireName).sorted().collect(Collectors.joining(", "));
    }
}
