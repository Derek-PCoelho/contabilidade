package br.com.contadoresassociados.folhas.application.clients;

import br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.util.function.Consumer;

/** Pendência 8.15: placeholders desconhecidos ou malformados são recusados no salvamento. */
public final class TemplatePlaceholderValidator implements Consumer<String> {

    public static final TemplatePlaceholderValidator INSTANCE = new TemplatePlaceholderValidator();

    @Override
    public void accept(String template) {
        var result = MessageTemplatePlaceholderCatalog.validate(template);
        if (!result.unknownKeys().isEmpty()) {
            throw new DomainValidationException("template.unknown_placeholder",
                    "Campo desconhecido no modelo: {{" + String.join("}}, {{", result.unknownKeys())
                            + "}}. Use apenas os campos da lista.");
        }
        if (!result.malformed().isEmpty()) {
            throw new DomainValidationException("template.malformed_placeholder",
                    "O modelo tem \"{{\" ou \"}}\" sem par. Confira as chaves dos campos.");
        }
    }
}
