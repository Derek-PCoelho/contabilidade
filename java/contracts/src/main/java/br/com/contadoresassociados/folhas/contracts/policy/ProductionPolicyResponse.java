package br.com.contadoresassociados.folhas.contracts.policy;

import java.util.List;

public record ProductionPolicyResponse(
        boolean enabled,
        String environmentName,
        String stage,
        boolean readyForSend,
        boolean currentUserRoleAllowed,
        boolean sendEnabled,
        int maximumBatchSize,
        int maximumDailySends,
        int authorizedSendsToday,
        int remainingSendsToday,
        String minimumApplicationVersion,
        List<String> allowedRoles,
        List<String> blockers) {

    public ProductionPolicyResponse {
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
