package br.com.contadoresassociados.folhas.desktop.ui;

import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionMethod;
import br.com.contadoresassociados.folhas.contracts.documents.RecognitionConfidence;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewGroupState;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import javafx.util.StringConverter;

/** Rótulos em português para os enums exibidos (equivalente ao {@code FriendlyTextConverter}). */
public final class FriendlyText {

    private FriendlyText() {
    }

    public static String of(Object value) {
        return switch (value) {
            case null -> "";
            case PersonTypeModel p -> switch (p) {
                case INDIVIDUAL -> "Pessoa física";
                case LEGAL_ENTITY -> "Empresa";
            };
            case ClientPartnerRoleModel r -> switch (r) {
                case MANAGING_PARTNER -> "Sócio-administrador";
                case PARTNER -> "Sócio";
                case ADMINISTRATOR -> "Administrador";
                case LEGAL_REPRESENTATIVE -> "Representante legal";
                case OTHER -> "Outro vínculo";
            };
            case ClientIdentifierTypeModel t -> switch (t) {
                case CNPJ -> "CNPJ";
                case CNPJ_ROOT -> "Raiz do CNPJ";
                case CPF -> "CPF";
                case INTERNAL_CODE -> "Código do sistema contábil";
                case LEGAL_NAME_ALIAS -> "Outro nome conhecido";
                case OTHER -> "Outro identificador";
            };
            case DeliveryRoleModel d -> switch (d) {
                case TO -> "Destinatário principal";
                case CC -> "Receber em cópia";
                case INTERNAL_COPY -> "Cópia interna do escritório";
            };
            case ReviewDocumentState s -> switch (s) {
                case BLOCKED -> "Precisa de correção";
                case READY -> "Pronto para agrupar";
                case GROUPED -> "Pronto para revisar";
                case APPROVED -> "Aprovado";
                case DUPLICATE -> "Documento repetido";
            };
            case ReviewGroupState g -> switch (g) {
                case BUILDING -> "Organizando documentos";
                case BLOCKED -> "Precisa de correção";
                case READY_FOR_REVIEW -> "Pronto para aprovar";
                case APPROVED -> "Aprovado";
            };
            case ValidationSeverity v -> switch (v) {
                case INFO -> "Informação";
                case WARNING -> "Atenção";
                case ERROR -> "Corrigir antes de continuar";
                case BLOCKER -> "Ação obrigatória";
            };
            case RecognitionConfidence c -> switch (c) {
                case LOW -> "Confiança baixa";
                case MEDIUM -> "Confiança média";
                case HIGH -> "Confiança alta";
            };
            case RecognizedDocumentType t -> DocumentPresentation.label(t);
            case SemanticFieldRole r -> switch (r) {
                case EMPLOYER_TAX_ID -> "CNPJ do empregador";
                case CLIENT_TAX_ID -> "Documento do cliente";
                case ESTABLISHMENT_TAX_ID -> "CNPJ da filial";
                case EMPLOYEE_CPF -> "CPF do empregado";
                case UNION_TAX_ID -> "CNPJ do sindicato";
                case DOCUMENT_ISSUER_TAX_ID -> "Documento do emissor";
                case PARTNER_CPF -> "CPF do sócio";
                case EMPLOYER_NAME -> "Nome do empregador";
                case CLIENT_NAME -> "Nome do cliente";
                case EMPLOYEE_NAME -> "Nome do empregado";
                case UNION_NAME -> "Nome do sindicato";
                case INTERNAL_CODE -> "Código interno";
                case COMPETENCE -> "Competência";
                case ASSESSMENT_PERIOD -> "Período de apuração";
                case DUE_DATE -> "Vencimento";
                case TOTAL_AMOUNT -> "Valor total";
                case VACATION_PERIOD -> "Período de férias";
                case EVENT_DATE -> "Data do evento";
                case UNKNOWN -> DocumentPresentation.label(r);
            };
            case ClientResolutionMethod m -> switch (m) {
                case NONE -> "Cliente ainda não identificado";
                case EXACT_CLIENT_TAX_ID -> "Documento principal do cliente";
                case EXACT_ESTABLISHMENT_TAX_ID -> "CNPJ de uma filial cadastrada";
                case UNIQUE_CNPJ_ROOT_AND_NAME -> "Raiz do CNPJ e nome da empresa";
                case EXACT_INDIVIDUAL_TAX_ID -> "CPF do cliente";
                case EXACT_INTERNAL_CODE -> "Código interno";
                case EXACT_LEGAL_NAME -> "Nome completo";
                case EXACT_ALIAS -> "Outro nome cadastrado";
                case FUZZY_SUGGESTION -> "Sugestão por nome — exige conferência";
                case MANUAL_OVERRIDE -> "Cliente confirmado manualmente";
            };
            default -> value.toString();
        };
    }

    public static <T> StringConverter<T> converter() {
        return new StringConverter<>() {
            @Override
            public String toString(T object) {
                return of(object);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
