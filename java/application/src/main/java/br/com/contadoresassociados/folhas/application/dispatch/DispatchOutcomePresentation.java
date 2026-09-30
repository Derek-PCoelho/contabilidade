package br.com.contadoresassociados.folhas.application.dispatch;

/** Texto honesto sobre o resultado de uma mensagem (nunca afirma entrega sem comprovação). */
public record DispatchOutcomePresentation(String operationResult, String deliveryStatus, String evidence,
        String nextAction, boolean technicalSuccess, boolean needsAttention, boolean simulation) {
}
