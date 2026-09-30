package br.com.contadoresassociados.folhas.application.documents;

/**
 * Contexto autenticado da revisão. {@code scopeKey} inclui a organização, garantindo que
 * workspaces de organizações diferentes nunca se misturem (pendência 3.14).
 */
public record DocumentReviewContext(String scopeKey, String actorId, String actorDisplayName) {
}
