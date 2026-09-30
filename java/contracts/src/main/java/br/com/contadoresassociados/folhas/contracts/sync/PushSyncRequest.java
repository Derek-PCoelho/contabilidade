package br.com.contadoresassociados.folhas.contracts.sync;

import java.util.List;

public record PushSyncRequest(
        List<UpsertClientCommand> commands) {

    public PushSyncRequest {
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    /** Limite aceito pelo servidor por requisição (o cliente pagina — pendência 2.5). */
    public static final int MAXIMUM_COMMANDS = 100;
}
