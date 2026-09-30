package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.DocumentReviewWorkspace;

/**
 * Persistência do workspace de revisão. {@link #save} deve rejeitar com
 * {@link WorkspaceConflictException} quando a versão gravada diferir de {@code expectedVersion}
 * (pendência 3.13: fim do lost update).
 */
public interface DocumentReviewStore {

    DocumentReviewWorkspace load(String scopeKey);

    DocumentReviewWorkspace save(DocumentReviewWorkspace workspace, long expectedVersion);
}
