package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderAccount;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderCapabilities;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderResult;

/**
 * Provedor de e-mail. Implementações não devem lançar exceção depois de iniciar a chamada
 * externa: qualquer falha nesse ponto deve virar {@code AMBIGUOUS}. O workflow ainda assim
 * protege contra exceções (pendência 5.4).
 */
public interface EmailProvider {

    String providerKey();

    EmailProviderAccount account();

    EmailProviderCapabilities capabilities();

    EmailProviderResult createDraft(EmailEnvelope envelope);

    EmailProviderResult send(EmailEnvelope envelope);

    EmailProviderResult reconcile(DeliveryAttempt attempt);
}
