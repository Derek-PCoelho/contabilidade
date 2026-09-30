package br.com.contadoresassociados.folhas.contracts.dispatch;

public record EmailProviderResult(
        DeliveryAttemptState state,
        String providerMessageId,
        String providerDraftId,
        String errorCode,
        String redactedError) {
}
