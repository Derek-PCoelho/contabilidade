package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;

/** Persistência do workspace de despacho com concorrência otimista (pendência 3.13). */
public interface DispatchWorkflowStore {

    DispatchWorkspace load(String scopeKey);

    DispatchWorkspace save(DispatchWorkspace workspace, long expectedVersion);
}
