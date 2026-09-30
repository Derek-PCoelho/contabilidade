package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Resolve o cliente a partir dos campos reconhecidos.
 *
 * <p>Pendência 4.6/2.7: {@link #resolveBatch} é o caminho principal (uma chamada por revalidação).
 * Pendência 2.6: {@link #minimize} descarta os campos fora dos 6 papéis elegíveis e substitui o
 * trecho de evidência pelo valor exibido; nenhum snippet bruto (com PII) sai do dispositivo.
 */
public interface ClientResolver {

    Set<SemanticFieldRole> ELIGIBLE_ROLES = EnumSet.of(SemanticFieldRole.EMPLOYER_TAX_ID,
            SemanticFieldRole.CLIENT_TAX_ID, SemanticFieldRole.ESTABLISHMENT_TAX_ID, SemanticFieldRole.EMPLOYER_NAME,
            SemanticFieldRole.CLIENT_NAME, SemanticFieldRole.INTERNAL_CODE);

    ClientResolutionResult resolve(ClientResolutionRequest request);

    default List<ClientResolutionResult> resolveBatch(List<ClientResolutionRequest> requests) {
        return requests.stream().map(this::resolve).toList();
    }

    static ClientResolutionRequest minimize(List<RecognizedField> fields) {
        return new ClientResolutionRequest(fields.stream()
                .filter(f -> ELIGIBLE_ROLES.contains(f.role()))
                .map(ClientResolver::minimizeField)
                .toList());
    }

    private static RecognizedField minimizeField(RecognizedField field) {
        var evidence = field.evidence();
        var sanitized = evidence == null ? null
                : new br.com.contadoresassociados.folhas.contracts.documents.EvidenceBox(evidence.pageNumber(),
                        evidence.x(), evidence.y(), evidence.width(), evidence.height(),
                        field.name() + ": " + field.displayValue());
        return new RecognizedField(field.name(), field.value(), field.displayValue(), field.role(),
                field.confidence(), sanitized);
    }
}
