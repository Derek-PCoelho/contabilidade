package br.com.contadoresassociados.folhas.contracts.documents;

public record RecognitionFinding(
        String code,
        String message,
        boolean isBlocker) {
}
