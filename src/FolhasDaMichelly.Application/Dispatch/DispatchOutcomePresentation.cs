using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Application.Dispatch;

public sealed record DispatchOutcomePresentation(
    string OperationResult,
    string DeliveryStatus,
    string Evidence,
    string NextAction,
    bool IsTechnicalSuccess,
    bool NeedsAttention,
    bool IsSimulation);

public static class DispatchOutcomePresenter
{
    public static DispatchOutcomePresentation Present(
        DispatchItem item,
        DeliveryAttempt? attempt)
    {
        ArgumentNullException.ThrowIfNull(item);
        var isSimulation = string.Equals(
            attempt?.ProviderKey,
            DispatchWorkflowOptions.FakeProviderKey,
            StringComparison.OrdinalIgnoreCase) ||
            item.Message?.SenderAccountId.StartsWith("fake://", StringComparison.OrdinalIgnoreCase) == true;

        if (isSimulation)
        {
            return PresentSimulation(item, attempt);
        }

        if (item.Mode == DispatchOperationMode.Draft || attempt?.State == DeliveryAttemptState.DraftCreated)
        {
            return new(
                "Rascunho criado na conta conectada",
                "Não houve envio ao destinatário",
                attempt?.ProviderDraftId is null ? "Sem protocolo de rascunho" : "Protocolo técnico do rascunho registrado",
                "Abra a conta conectada e confira o rascunho.",
                true,
                false,
                false);
        }

        if (item.State == DispatchItemState.AcceptedByProvider ||
            attempt?.State == DeliveryAttemptState.AcceptedByProvider)
        {
            var test = item.Mode == DispatchOperationMode.Test;
            return new(
                test ? "Teste aceito pelo serviço de e-mail" : "Solicitação aceita pelo serviço de e-mail",
                test
                    ? "O cliente original não foi destinatário; a entrega na caixa de teste não foi confirmada"
                    : "Entrega ao destinatário não confirmada",
                attempt?.ProviderMessageId is null
                    ? "Sem protocolo de entrega"
                    : "Protocolo técnico do serviço registrado; não é comprovante de entrega",
                "Consulte a conta ou o serviço sem repetir a operação.",
                true,
                false,
                false);
        }

        if (item.State == DispatchItemState.Ambiguous || attempt?.State == DeliveryAttemptState.Ambiguous)
        {
            return new(
                "Resultado incerto",
                "Entrega desconhecida",
                "Não há confirmação conclusiva",
                "Use Conferir situação e não repita a operação.",
                false,
                true,
                false);
        }

        if (item.State == DispatchItemState.Failed || attempt?.State is
            DeliveryAttemptState.FailedPermanent or DeliveryAttemptState.FailedTransient)
        {
            return new(
                "Operação não concluída",
                "Sem confirmação de entrega",
                "Falha registrada no histórico",
                attempt?.State == DeliveryAttemptState.FailedTransient
                    ? "Corrija a causa e só então faça uma nova tentativa consciente."
                    : "Corrija a causa; não repita enquanto o bloqueio permanecer.",
                false,
                true,
                false);
        }

        if (item.State == DispatchItemState.Reconciled || attempt?.State == DeliveryAttemptState.Reconciled)
        {
            return new(
                "Situação consultada no serviço",
                "A consulta não comprova entrega ou leitura",
                "Reconciliação registrada sem novo envio",
                "Confira a conta conectada se ainda houver dúvida.",
                true,
                false,
                false);
        }

        if (item.State is DispatchItemState.Sending or DispatchItemState.DraftCreating ||
            attempt?.State == DeliveryAttemptState.Pending)
        {
            return new(
                "Operação em processamento",
                "Entrega ainda desconhecida",
                "Tentativa pendente registrada",
                "Aguarde ou use Conferir situação; não repita.",
                false,
                true,
                false);
        }

        return new(
            item.State switch
            {
                DispatchItemState.Blocked => "Mensagem bloqueada por pendência",
                DispatchItemState.ReadyForApproval => "Mensagem pronta para conferência",
                DispatchItemState.Approved => "Mensagem aprovada e ainda não executada",
                DispatchItemState.Cancelled => "Operação cancelada",
                _ => "Mensagem ainda não executada",
            },
            "Não houve envio",
            "Sem protocolo de serviço",
            item.State == DispatchItemState.Blocked
                ? "Resolva as pendências antes de continuar."
                : "Siga a próxima etapa exibida no aplicativo.",
            false,
            item.State == DispatchItemState.Blocked,
            false);
    }

    private static DispatchOutcomePresentation PresentSimulation(
        DispatchItem item,
        DeliveryAttempt? attempt)
    {
        if (item.State == DispatchItemState.Ambiguous || attempt?.State == DeliveryAttemptState.Ambiguous)
        {
            return new(
                "Teste local com resultado incerto",
                "Nenhum e-mail real foi enviado",
                "Registro local inconclusivo",
                "Confira o histórico local antes de repetir o teste.",
                false,
                true,
                true);
        }

        if (item.State == DispatchItemState.Failed || attempt?.State is
            DeliveryAttemptState.FailedPermanent or DeliveryAttemptState.FailedTransient)
        {
            return new(
                "Teste local não concluído",
                "Nenhum e-mail real foi enviado",
                "Falha da simulação registrada localmente",
                "Corrija a causa indicada e repita somente o teste local.",
                false,
                true,
                true);
        }

        if (item.State == DispatchItemState.DraftCreated ||
            attempt?.State == DeliveryAttemptState.DraftCreated)
        {
            return new(
                "Simulação de rascunho concluída",
                "Nenhum e-mail real foi enviado",
                "Rascunho registrado somente neste aplicativo",
                "Nenhuma conferência de caixa postal é necessária.",
                true,
                false,
                true);
        }

        if (item.State is DispatchItemState.AcceptedByProvider or DispatchItemState.Completed or DispatchItemState.Reconciled ||
            attempt?.State is DeliveryAttemptState.AcceptedByProvider or DeliveryAttemptState.Reconciled)
        {
            return new(
                "Simulação local concluída",
                "Nenhum e-mail real foi enviado",
                "Resultado registrado somente neste aplicativo",
                "Nenhuma conferência de caixa postal é necessária.",
                true,
                false,
                true);
        }

        return new(
            "Simulação local ainda não concluída",
            "Nenhum e-mail real foi enviado",
            "Sem registro final da simulação",
            "Conclua as etapas do teste dentro do aplicativo.",
            false,
            item.State == DispatchItemState.Blocked,
            true);
    }
}
