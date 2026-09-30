package br.com.contadoresassociados.folhas.contracts.policy;

public record AppReleasePolicyResponse(
        String minimumSupportedVersion,
        boolean isSupported,
        boolean emailSendEnabled,
        boolean betaChannelEnabled,
        boolean stableChannelEnabled) {
}
