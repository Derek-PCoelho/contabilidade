package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import java.util.List;
import java.util.Set;

public record DocumentReviewProfile(
        RecognizedDocumentType documentType,
        String version,
        Set<SemanticFieldRole> requiredRoles,
        List<SemanticFieldRole> semanticIdentityRoles,
        String groupingPolicyCode,
        boolean includeEstablishmentInGrouping,
        boolean groupIndividually,
        boolean requirePeriod,
        boolean requirePositiveAmount) {

    public DocumentReviewProfile {
        requiredRoles = Set.copyOf(requiredRoles);
        semanticIdentityRoles = List.copyOf(semanticIdentityRoles);
    }
}
