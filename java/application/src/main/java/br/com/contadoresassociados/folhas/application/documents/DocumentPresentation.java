package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;

/** Rótulos em português para tipos e campos (nada de nome de enum na tela). */
public final class DocumentPresentation {

    private DocumentPresentation() {
    }

    public static String label(RecognizedDocumentType type) {
        return switch (type) {
            case UNCLASSIFIED -> "Tipo não identificado";
            case VACATION -> "Férias";
            case FGTS_DIGITAL -> "FGTS Digital";
            case PAYROLL -> "Folha de pagamento";
            case FEDERAL_REVENUE_COLLECTION -> "Guia de arrecadação";
            case THIRTEENTH_SALARY -> "13º salário";
            case PRO_LABORE -> "Pró-labore";
            case TERMINATION -> "Rescisão";
        };
    }

    public static String label(SemanticFieldRole role) {
        return switch (role) {
            case UNKNOWN -> "Campo não identificado";
            case EMPLOYER_TAX_ID -> "CNPJ do empregador";
            case CLIENT_TAX_ID -> "CPF/CNPJ do contribuinte";
            case ESTABLISHMENT_TAX_ID -> "CNPJ do estabelecimento";
            case EMPLOYEE_CPF -> "CPF do empregado";
            case UNION_TAX_ID -> "CNPJ do sindicato";
            case DOCUMENT_ISSUER_TAX_ID -> "CNPJ do emissor";
            case PARTNER_CPF -> "CPF do sócio";
            case EMPLOYER_NAME -> "Nome do empregador";
            case CLIENT_NAME -> "Nome do contribuinte";
            case EMPLOYEE_NAME -> "Nome do empregado";
            case UNION_NAME -> "Nome do sindicato";
            case INTERNAL_CODE -> "Código interno";
            case COMPETENCE -> "Competência";
            case ASSESSMENT_PERIOD -> "Período de apuração";
            case DUE_DATE -> "Vencimento";
            case TOTAL_AMOUNT -> "Valor total";
            case VACATION_PERIOD -> "Período de férias";
            case EVENT_DATE -> "Data do evento";
        };
    }
}
