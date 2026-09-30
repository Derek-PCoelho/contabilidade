package br.com.contadoresassociados.folhas.contracts.policy;

public record PilotPolicyResponse(
        boolean enabled,
        String environmentName,
        boolean allowTest,
        boolean allowDraft,
        boolean allowSend,
        boolean requireNonProductionData,
        int maximumClients,
        String minimumApplicationVersion) {
}
