# Protocolo de sincronização — Fase 3

## Push

`POST /api/sync/clients` aceita de 1 a 100 `UpsertClientCommand`. Cada comando contém `OperationId`, `ClientId`, valor, estado ativo e `ExpectedVersion`; nunca contém tenant. Resultados possíveis:

- `Applied`: gravado e auditado;
- `Duplicate`: `OperationId` já conhecido, sem segundo efeito/audit;
- `Conflict`: versão divergente, com registro central atual;
- `Rejected`: comando inválido.

## Pull

`GET /api/sync/clients?checkpoint=N` retorna mudanças posteriores ao checkpoint para a organização autenticada. O novo checkpoint somente é persistido localmente junto com a aplicação do cache.

## Offline e SignalR

A fila sobrevive a reinício. Erro HTTP mantém o comando e agenda retry exponencial limitado a cinco minutos, com jitter. Conflito não é repetido automaticamente. O hub `/hubs/sync` exige `clients.read`, valida o dispositivo e associa a conexão apenas ao grupo `organization:{id}`. `RecordsChanged` é um aviso para executar pull.

O catálogo rico usa a API `/api/clients` e armazena snapshots descartáveis em `catalog_cache`. Escritas de cliente ou template acrescentam um checkpoint com `EntityType` (`client-catalog` ou `message-template`) e enviam `RecordsChanged`; a notificação nunca contém CPF/CNPJ, e-mail ou o registro completo.

## Invariantes

- API é a única fronteira central; Desktop não acessa PostgreSQL;
- idempotência é por organização;
- conflito é explícito;
- tenant não vem do payload;
- PDFs e caminhos locais não sincronizam;
- não existe fallback de envio: e-mail não foi implementado nesta fase.
