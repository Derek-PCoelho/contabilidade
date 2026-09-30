package br.com.contadoresassociados.folhas.application.documents;

import static br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole.*;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Perfis de revisão por tipo documental.
 *
 * <p>Pendência 2.9: as chaves de duplicidade semântica agora incluem identidades fiscais do
 * documento (CNPJ do estabelecimento para folha/FGTS/DARF; todos os CPFs de empregados no 13º),
 * em vez de só cliente+tipo+período.
 */
public final class DocumentReviewOptions {

    public static final String POLICY_VERSION = "java-review-v2";

    private final Map<RecognizedDocumentType, DocumentReviewProfile> profiles;
    private final int minimumOverrideReasonLength;
    private final boolean pastDueDateProducesWarning;

    public DocumentReviewOptions(Map<RecognizedDocumentType, DocumentReviewProfile> profiles,
            int minimumOverrideReasonLength, boolean pastDueDateProducesWarning) {
        this.profiles = Map.copyOf(profiles);
        this.minimumOverrideReasonLength = minimumOverrideReasonLength;
        this.pastDueDateProducesWarning = pastDueDateProducesWarning;
    }

    public static DocumentReviewOptions defaults() {
        var employer = Set.of(EMPLOYER_TAX_ID, TOTAL_AMOUNT);
        var federal = Set.of(CLIENT_TAX_ID, TOTAL_AMOUNT);
        var map = new EnumMap<RecognizedDocumentType, DocumentReviewProfile>(RecognizedDocumentType.class);
        map.put(RecognizedDocumentType.PAYROLL, profile(RecognizedDocumentType.PAYROLL, employer,
                List.of(ESTABLISHMENT_TAX_ID), "monthly-accounting", false));
        map.put(RecognizedDocumentType.FGTS_DIGITAL, profile(RecognizedDocumentType.FGTS_DIGITAL, employer,
                List.of(ESTABLISHMENT_TAX_ID), "monthly-accounting", false));
        map.put(RecognizedDocumentType.FEDERAL_REVENUE_COLLECTION, profile(
                RecognizedDocumentType.FEDERAL_REVENUE_COLLECTION, federal, List.of(DUE_DATE, TOTAL_AMOUNT),
                "monthly-accounting", false));
        map.put(RecognizedDocumentType.THIRTEENTH_SALARY, profile(RecognizedDocumentType.THIRTEENTH_SALARY, employer,
                List.of(EMPLOYEE_CPF), "monthly-accounting", false));
        map.put(RecognizedDocumentType.PRO_LABORE, profile(RecognizedDocumentType.PRO_LABORE, employer,
                List.of(PARTNER_CPF), "monthly-accounting", false));
        map.put(RecognizedDocumentType.VACATION, profile(RecognizedDocumentType.VACATION, employer,
                List.of(EMPLOYEE_CPF), "vacation-event", true));
        map.put(RecognizedDocumentType.TERMINATION, profile(RecognizedDocumentType.TERMINATION, employer,
                List.of(EMPLOYEE_CPF), "termination-event", true));
        return new DocumentReviewOptions(map, 10, true);
    }

    private static DocumentReviewProfile profile(RecognizedDocumentType type, Set<SemanticFieldRole> required,
            List<SemanticFieldRole> identity, String policy, boolean individually) {
        return new DocumentReviewProfile(type, POLICY_VERSION, required, identity, policy, true, individually, true,
                true);
    }

    public DocumentReviewProfile profile(RecognizedDocumentType type) {
        return profiles.get(type);
    }

    public int minimumOverrideReasonLength() {
        return minimumOverrideReasonLength;
    }

    public boolean pastDueDateProducesWarning() {
        return pastDueDateProducesWarning;
    }
}
