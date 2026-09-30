package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchBlock;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.RenderedMessageSnapshot;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import java.util.List;

public interface DispatchMessageComposer {

    record Composition(RenderedMessageSnapshot message, List<DispatchBlock> blocks) {
        public Composition {
            blocks = List.copyOf(blocks);
        }
    }

    Composition compose(DocumentDispatchGroup group, List<ReviewDocument> documents, DispatchOperationMode mode,
            String testDestination, DispatchExecutionContext context);
}
