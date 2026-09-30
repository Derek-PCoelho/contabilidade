package br.com.contadoresassociados.folhas.contracts.clients;

/** Pendência 4.19: arquivamento de cliente e modelo também no servidor. */
public record ArchiveRequest(
        long expectedVersion,
        String reason) {
}
