package br.com.contadoresassociados.folhas.contracts.dispatch;

public record EmailProviderAccount(
        String providerKey,
        String accountId,
        String displayName,
        boolean isConnected) {
}
