package br.com.contadoresassociados.folhas.application.clients;

import br.com.contadoresassociados.folhas.contracts.clients.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catálogo de clientes e modelos — mesma interface para os perfis Local (SQLite) e Conectado
 * (API). Pendência 4.19/6.12: arquivamento e ativação existem nos dois perfis.
 */
public interface ClientCatalogService {

    ClientSearchResponse search(String search, Boolean active, PersonTypeModel personType, int skip, int take);

    Optional<ClientDetails> get(UUID clientId);

    /** Consulta em lote (pendência 6.13: sem N+1 na tela de clientes). */
    List<ClientDetails> getMany(List<UUID> clientIds);

    ClientDetails save(UUID clientId, ClientMutationRequest request);

    Optional<ClientReadinessResponse> readiness(UUID clientId);

    List<MessageTemplateModel> templates(UUID clientId, boolean includeInactive);

    MessageTemplateModel saveTemplate(UUID templateId, MessageTemplateMutationRequest request);

    ClientDetails setClientActive(UUID clientId, long expectedVersion, boolean active);

    MessageTemplateModel setTemplateActive(UUID templateId, long expectedVersion, boolean active);

    void archiveClient(UUID clientId, ArchiveRequest request);

    void archiveTemplate(UUID templateId, ArchiveRequest request);

    ClientCatalogTransferDocument export();

    ClientCatalogImportResult importCatalog(ClientCatalogImportRequest request);

    List<AuditEventModel> audit(UUID clientId);
}
