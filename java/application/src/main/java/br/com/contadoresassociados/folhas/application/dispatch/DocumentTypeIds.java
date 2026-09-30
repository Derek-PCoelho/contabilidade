package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Identificadores estáveis por tipo documental (pendências 6.12 e 10.16): permitem cadastrar
 * destinatários e modelos específicos por tipo (ex.: DARF só para o financeiro).
 */
public final class DocumentTypeIds {

    private DocumentTypeIds() {
    }

    public static UUID of(RecognizedDocumentType type) {
        return UUID.nameUUIDFromBytes(("folhas.document-type:" + type.name()).getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<RecognizedDocumentType> typeOf(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (var t : RecognizedDocumentType.values()) {
            if (of(t).equals(id)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
