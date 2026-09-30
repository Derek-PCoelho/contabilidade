package br.com.contadoresassociados.folhas.desktop.ui;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;

/** Tradução de falhas para mensagens de tela (equivalente ao {@code ExecuteAsync} da versão .NET). */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    public static String friendly(Throwable error) {
        return switch (error) {
            case CatalogException.Concurrency c -> "Conflito de edição: recarregue o cadastro antes de salvar novamente.";
            case ConcurrencyConflictException c -> "Conflito de edição: recarregue o cadastro antes de salvar novamente.";
            case CatalogException c -> c.getMessage();
            case DomainValidationException d -> d.getMessage();
            case IllegalStateException s when s.getMessage() != null -> s.getMessage();
            case IllegalArgumentException a -> "Confira os dados informados e tente novamente.";
            case java.io.UncheckedIOException io -> "Não foi possível falar com o servidor. Verifique a conexão e tente novamente.";
            default -> "Não foi possível concluir esta ação. Nenhuma alteração incompleta foi confirmada.";
        };
    }
}
