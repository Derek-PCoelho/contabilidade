package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;

import java.util.Locale;
import java.util.Objects;

/**
 * Apresentação de resultado. Correção 2.2: estados de incerteza, falha e processamento
 * são avaliados antes do modo Draft, para que um rascunho que falhou nunca apareça como criado.
 */
public final class DispatchOutcomePresenter {

    private DispatchOutcomePresenter() {
    }

    public static DispatchOutcomePresentation present(DispatchItem item, DeliveryAttempt attempt) {
        Objects.requireNonNull(item, "item");
        DeliveryAttemptState a = attempt == null ? null : attempt.state();
        boolean simulation = (attempt != null && attempt.providerKey() != null
                && attempt.providerKey().equalsIgnoreCase(DispatchWorkflowOptions.FAKE_PROVIDER))
                || (item.message() != null && item.message().senderAccountId() != null
                && item.message().senderAccountId().toLowerCase(Locale.ROOT).startsWith("fake://"));
        if (simulation) {
            return presentSimulation(item, a);
        }

        if (item.state() == DispatchItemState.AMBIGUOUS || a == DeliveryAttemptState.AMBIGUOUS) {
            return new DispatchOutcomePresentation("Resultado incerto", "Entrega desconhecida",
                    "Não há confirmação conclusiva", "Use Conferir situação e não repita a operação.",
                    false, true, false);
        }
        if (item.state() == DispatchItemState.FAILED || a == DeliveryAttemptState.FAILED_PERMANENT
                || a == DeliveryAttemptState.FAILED_TRANSIENT) {
            return new DispatchOutcomePresentation("Operação não concluída", "Sem confirmação de entrega",
                    "Falha registrada no histórico",
                    a == DeliveryAttemptState.FAILED_TRANSIENT
                            ? "Corrija a causa e só então faça uma nova tentativa consciente."
                            : "Corrija a causa; não repita enquanto o bloqueio permanecer.",
                    false, true, false);
        }
        if (item.state() == DispatchItemState.SENDING || item.state() == DispatchItemState.DRAFT_CREATING
                || a == DeliveryAttemptState.PENDING) {
            return new DispatchOutcomePresentation("Operação em processamento", "Entrega ainda desconhecida",
                    "Tentativa pendente registrada", "Aguarde ou use Conferir situação; não repita.",
                    false, true, false);
        }
        if (a == DeliveryAttemptState.DRAFT_CREATED || item.state() == DispatchItemState.DRAFT_CREATED
                || (item.mode() == DispatchOperationMode.DRAFT && item.state() == DispatchItemState.COMPLETED)) {
            return new DispatchOutcomePresentation("Rascunho criado na conta conectada",
                    "Não houve envio ao destinatário",
                    attempt == null || attempt.providerDraftId() == null ? "Sem protocolo de rascunho"
                            : "Protocolo técnico do rascunho registrado",
                    "Abra a conta conectada e confira o rascunho.", true, false, false);
        }
        if (item.state() == DispatchItemState.ACCEPTED_BY_PROVIDER || a == DeliveryAttemptState.ACCEPTED_BY_PROVIDER) {
            boolean test = item.mode() == DispatchOperationMode.TEST;
            return new DispatchOutcomePresentation(
                    test ? "Teste aceito pelo serviço de e-mail" : "Solicitação aceita pelo serviço de e-mail",
                    test ? "O cliente original não foi destinatário; a entrega na caixa de teste não foi confirmada"
                            : "Entrega ao destinatário não confirmada",
                    attempt == null || attempt.providerMessageId() == null ? "Sem protocolo de entrega"
                            : "Protocolo técnico do serviço registrado; não é comprovante de entrega",
                    "Consulte a conta ou o serviço sem repetir a operação.", true, false, false);
        }
        if (item.state() == DispatchItemState.RECONCILED || a == DeliveryAttemptState.RECONCILED) {
            return new DispatchOutcomePresentation("Situação consultada no serviço",
                    "A consulta não comprova entrega ou leitura", "Reconciliação registrada sem novo envio",
                    "Confira a conta conectada se ainda houver dúvida.", true, false, false);
        }
        if (item.mode() == DispatchOperationMode.DRAFT && item.state() == DispatchItemState.APPROVED) {
            return new DispatchOutcomePresentation("Rascunho aprovado e ainda não criado", "Não houve envio",
                    "Sem protocolo de serviço", "Siga a próxima etapa exibida no aplicativo.", false, false, false);
        }
        String result = switch (item.state()) {
            case BLOCKED -> "Mensagem bloqueada por pendência";
            case READY_FOR_APPROVAL -> "Mensagem pronta para conferência";
            case APPROVED -> "Mensagem aprovada e ainda não executada";
            case CANCELLED -> "Operação cancelada";
            default -> "Mensagem ainda não executada";
        };
        boolean blocked = item.state() == DispatchItemState.BLOCKED;
        return new DispatchOutcomePresentation(result, "Não houve envio", "Sem protocolo de serviço",
                blocked ? "Resolva as pendências antes de continuar." : "Siga a próxima etapa exibida no aplicativo.",
                false, blocked, false);
    }

    private static DispatchOutcomePresentation presentSimulation(DispatchItem item, DeliveryAttemptState a) {
        DispatchItemState s = item.state();
        if (s == DispatchItemState.AMBIGUOUS || a == DeliveryAttemptState.AMBIGUOUS) {
            return new DispatchOutcomePresentation("Teste local com resultado incerto", "Nenhum e-mail real foi enviado",
                    "Registro local inconclusivo", "Confira o histórico local antes de repetir o teste.",
                    false, true, true);
        }
        if (s == DispatchItemState.FAILED || a == DeliveryAttemptState.FAILED_PERMANENT
                || a == DeliveryAttemptState.FAILED_TRANSIENT) {
            return new DispatchOutcomePresentation("Teste local não concluído", "Nenhum e-mail real foi enviado",
                    "Falha da simulação registrada localmente",
                    "Corrija a causa indicada e repita somente o teste local.", false, true, true);
        }
        if (s == DispatchItemState.DRAFT_CREATED || a == DeliveryAttemptState.DRAFT_CREATED) {
            return new DispatchOutcomePresentation("Simulação de rascunho concluída", "Nenhum e-mail real foi enviado",
                    "Rascunho registrado somente neste aplicativo", "Nenhuma conferência de caixa postal é necessária.",
                    true, false, true);
        }
        if (s == DispatchItemState.ACCEPTED_BY_PROVIDER || s == DispatchItemState.COMPLETED
                || s == DispatchItemState.RECONCILED || a == DeliveryAttemptState.ACCEPTED_BY_PROVIDER
                || a == DeliveryAttemptState.RECONCILED) {
            return new DispatchOutcomePresentation("Simulação local concluída", "Nenhum e-mail real foi enviado",
                    "Resultado registrado somente neste aplicativo", "Nenhuma conferência de caixa postal é necessária.",
                    true, false, true);
        }
        return new DispatchOutcomePresentation("Simulação local ainda não concluída", "Nenhum e-mail real foi enviado",
                "Sem registro final da simulação", "Conclua as etapas do teste dentro do aplicativo.",
                false, s == DispatchItemState.BLOCKED, true);
    }
}
