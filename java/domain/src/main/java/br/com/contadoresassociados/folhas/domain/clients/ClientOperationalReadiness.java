package br.com.contadoresassociados.folhas.domain.clients;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Prontidão operacional do cliente para envio.
 *
 * <p>Pendência 1.3: além de "inativo" e "sem destinatário To", agora avalia os códigos
 * de e-mail inválido (que existiam na versão .NET mas nunca eram emitidos) e a matriz ausente.
 */
public record ClientOperationalReadiness(boolean eligible, List<String> blockCodes) {

    public static final String CLIENT_INACTIVE = "CLIENT_INACTIVE";
    public static final String NO_ACTIVE_TO_RECIPIENT = "NO_ACTIVE_TO_RECIPIENT";
    public static final String PARTNER_EMAIL_INVALID = "PARTNER_EMAIL_INVALID";
    public static final String RECIPIENT_EMAIL_INVALID = "RECIPIENT_EMAIL_INVALID";

    public ClientOperationalReadiness {
        blockCodes = List.copyOf(blockCodes);
    }

    public static ClientOperationalReadiness evaluate(Client client, LocalDate today) {
        Objects.requireNonNull(client, "client");
        var blocks = new ArrayList<String>();
        if (!client.active()) {
            blocks.add(CLIENT_INACTIVE);
        }
        if (client.recipients().stream().noneMatch(r -> r.deliveryRole() == DeliveryRole.TO && r.isCurrentlyValid(today))) {
            blocks.add(NO_ACTIVE_TO_RECIPIENT);
        }
        if (client.recipients().stream().anyMatch(r -> r.isCurrentlyValid(today) && !EmailAddress.isValid(r.emailNormalized()))) {
            blocks.add(RECIPIENT_EMAIL_INVALID);
        }
        if (client.partners().stream().anyMatch(p -> p.active() && p.emailNormalized() != null
                && !EmailAddress.isValid(p.emailNormalized()))) {
            blocks.add(PARTNER_EMAIL_INVALID);
        }
        return new ClientOperationalReadiness(blocks.isEmpty(), blocks);
    }
}
