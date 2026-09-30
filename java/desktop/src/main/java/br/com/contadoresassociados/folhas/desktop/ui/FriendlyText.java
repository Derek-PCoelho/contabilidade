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
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import java.util.Collection;
import java.util.stream.Collectors;
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
            case DispatchOperationMode m -> switch (m) {
                case TEST -> "Teste seguro";
                case DRAFT -> "Salvar como rascunho";
                case SEND -> "Enviar aos destinatários";
            };
            case FakeDeliveryScenario f -> switch (f) {
                case SUCCESS -> "Funcionamento normal";
                case TRANSIENT_FAILURE -> "Falha temporária";
                case PERMANENT_FAILURE -> "Falha permanente";
                case TIMEOUT -> "Tempo esgotado";
                case AMBIGUOUS -> "Resultado incerto";
            };
            case DispatchItemState d -> switch (d) {
                case BLOCKED -> "Precisa de correção";
                case READY_FOR_APPROVAL -> "Pronto para conferir";
                case APPROVED -> "Aprovado";
                case DRAFT_CREATING -> "Criando rascunho";
                case DRAFT_CREATED -> "Rascunho criado";
                case SENDING -> "Enviando";
                case ACCEPTED_BY_PROVIDER -> "Recebido pelo serviço de e-mail";
                case FAILED -> "Não concluído";
                case AMBIGUOUS -> "Resultado incerto — conferir";
                case RECONCILED -> "Situação conferida";
                case COMPLETED -> "Concluído";
                case CANCELLED -> "Cancelado";
            };
            case br.com.contadoresassociados.folhas.application.incidents.IncidentManagement.Category c -> switch (c) {
                case POTENTIAL_WRONG_RECIPIENT -> "Possível destinatário incorreto";
                case POTENTIAL_WRONG_ATTACHMENT -> "Possível anexo incorreto";
                case DUPLICATE_DELIVERY -> "Possível envio repetido";
                case AMBIGUOUS_PROVIDER_RESULT -> "Resultado incerto do provedor";
                case CREDENTIAL_EXPOSURE -> "Possível exposição de acesso";
                case LOCAL_DATA_EXPOSURE -> "Possível exposição de arquivo local";
                case OTHER -> "Outra ocorrência";
            };
            case br.com.contadoresassociados.folhas.application.incidents.IncidentManagement.Severity v -> switch (v) {
                case LOW -> "Baixa";
                case MEDIUM -> "Média";
                case HIGH -> "Alta";
                case CRITICAL -> "Crítica";
            };
            case br.com.contadoresassociados.folhas.application.incidents.IncidentManagement.Status st -> switch (st) {
                case OPEN -> "Aberto";
                case CONTAINED -> "Contido";
                case INVESTIGATING -> "Em apuração";
                case RESOLVED -> "Resolvido";
                case CLOSED -> "Encerrado";
            };
            case Collection<?> values -> values.stream().map(FriendlyText::of).collect(Collectors.joining(", "));
            default -> value.toString();
        };
    }

    /** Ações e resultados de auditoria em linguagem simples ({@code TranslateAction} da versão .NET). */
    public static String action(String value) {
        if (value == null) {
            return "";
        }
        return switch (value) {
            case "unauthenticated-operator", "local-operator" -> "Operador local";
            case "unauthenticated-connected-operator" -> "Operador conectado";
            case "created" -> "Cadastro criado";
            case "updated" -> "Cadastro atualizado";
            case "deactivated" -> "Cadastro inativado";
            case "reactivated" -> "Cadastro reativado";
            case "archived" -> "Excluído da lista — auditoria preservada";
            case "imported" -> "Cadastro restaurado";
            case "document.imported", "document_imported" -> "Documento importado";
            case "workspace.revalidated" -> "Documentos analisados novamente";
            case "workspace_revalidated" -> "Documentos conferidos novamente";
            case "document.period_corrected" -> "Competência corrigida";
            case "document.period_restored" -> "Competência reconhecida restaurada";
            case "document.removed_from_review" -> "Documento retirado da revisão";
            case "document.client_overridden" -> "Cliente confirmado manualmente";
            case "document.client_change_confirmed" -> "Mudança de cliente confirmada";
            case "document.validated" -> "Documento conferido";
            case "document.grouped" -> "Documento incluído em um conjunto";
            case "group.split", "group_split" -> "Conjunto separado";
            case "group.merged", "groups_merged" -> "Conjuntos unidos";
            case "group.empty_removed" -> "Conjunto vazio retirado";
            case "group.approved", "group_approved" -> "Conjunto liberado para mensagem";
            case "group.approval_invalidated" -> "Aprovação revogada após alteração";
            case "groups.selection_approved" -> "Conjuntos selecionados liberados";
            case "groups.client_approved" -> "Conjuntos prontos do cliente liberados";
            case "groups.bulk_approved" -> "Conjuntos prontos do mês liberados";
            case "client_overridden" -> "Cliente corrigido manualmente";
            case "dispatch_prepared", "dispatch_composed" -> "Mensagem preparada";
            case "dispatch_approval_invalidated" -> "Aprovação da mensagem revogada após alteração";
            case "dispatch_approved" -> "Mensagem aprovada";
            case "dispatch_bulk_approved" -> "Mensagens prontas do mês aprovadas";
            case "dispatch_batch_paused" -> "Sequência de mensagens pausada";
            case "provider_call_started" -> "Operação de e-mail iniciada";
            case "provider_call_completed" -> "Operação no serviço de e-mail concluída";
            case "provider_reconciled" -> "Situação consultada sem repetir a operação";
            case "dispatch_completed" -> "Operação de e-mail concluída";
            case "dispatch_reconciled" -> "Resultado conferido";
            case "reports_exported" -> "Relatórios exportados";
            case "email_provider_connected" -> "Conta de e-mail conectada";
            case "email_provider_disconnected" -> "Conta de e-mail desconectada";
            case "connected" -> "Conectado";
            case "disconnected" -> "Desconectado";
            case "completed", "COMPLETED", "Completed" -> "Concluído";
            case "pending" -> "Em andamento";
            case "failed", "Failed", "FAILED" -> "Não concluído";
            case "paused" -> "Pausado";
            case "ReadyForApproval", "READY_FOR_APPROVAL" -> "Pronto para aprovação";
            case "Approved", "APPROVED" -> "Aprovado";
            case "BLOCKED" -> "Precisa de correção";
            case "DraftCreated", "DRAFT_CREATED" -> "Rascunho criado";
            case "AcceptedByProvider", "ACCEPTED_BY_PROVIDER" -> "Aceito pelo serviço de e-mail";
            case "FAILED_TRANSIENT" -> "Falha temporária";
            case "FAILED_PERMANENT" -> "Falha permanente";
            case "Ambiguous", "AMBIGUOUS" -> "Resultado incerto";
            case "Reconciled", "RECONCILED" -> "Situação conferida";
            case "Cancelled", "CANCELLED" -> "Cancelado";
            case "Snapshot de conteúdo e agrupamento; não autoriza envio de e-mail." ->
                    "Conteúdo e organização registrados; isto ainda não envia e-mail.";
            default -> value.replace('_', ' ');
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
