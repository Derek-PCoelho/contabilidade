package br.com.contadoresassociados.folhas.contracts.dispatch;

public record EmailProviderCapabilities(
        boolean supportsDrafts,
        boolean supportsSending,
        boolean supportsReconciliation,
        long maximumAttachmentBytes) {
}
