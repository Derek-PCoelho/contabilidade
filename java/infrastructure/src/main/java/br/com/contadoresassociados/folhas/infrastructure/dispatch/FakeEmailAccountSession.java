package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;

/** Sessão sempre conectada do modo local seguro. */
public final class FakeEmailAccountSession implements EmailAccountSession {

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.FAKE_PROVIDER;
    }

    @Override
    public Status status() {
        return new Status(providerKey(), true, true, FakeEmailProvider.ACCOUNT_ID, "Modo seguro local", null);
    }

    @Override
    public Status connect() {
        return status();
    }

    @Override
    public void disconnect() {
        // nada a desconectar
    }

    @Override
    public String accessToken(boolean requireSendPermission) {
        return "";
    }
}
