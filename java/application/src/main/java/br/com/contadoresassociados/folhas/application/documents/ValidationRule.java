package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.ValidationFinding;
import java.util.List;

public interface ValidationRule {

    String code();

    List<ValidationFinding> evaluate(DocumentValidationContext context);
}
