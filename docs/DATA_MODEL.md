# Modelo de dados — Fase 12

## PostgreSQL central

| Agregado/tabela | Chave/isolamento | Finalidade |
|---|---|---|
| `organizations` | `Id`; `Slug` único | fronteira de tenant |
| `users`, `roles`, `user_roles` | UUID; usuário contém `OrganizationId` | identidade e RBAC |
| `device_sessions` | UUID + organização + usuário | sessão revogável por dispositivo |
| `sync_clients` | UUID + `OrganizationId` + `Version` | scaffold legado do protocolo incremental da Fase 2 |
| `clients` | UUID + organização; CPF/CNPJ e código únicos; `Version` | agregado cadastral PF/PJ, ativo/inativo e defaults |
| `client_identifiers` | UUID + cliente/organização; tipo+valor únicos | CNPJ, raiz, CPF, código, alias e papel semântico |
| `client_establishments` | UUID + cliente; CNPJ único por organização | matriz/filial, raiz, nomes e código interno |
| `client_recipients` | UUID + cliente; rota explícita única | destinatário `To`/`Cc`/`InternalCopy`, validade e escopo opcional |
| `client_partners` | UUID + cliente/organização | sócio ou representante, papel e CPF opcional; nunca resolve o cliente |
| `message_templates` | UUID + organização; cliente opcional; `Version` | assunto, corpo, assinatura e default; sem envio |
| `sync_operations` | `(OrganizationId, OperationId)` | deduplicação idempotente |
| `sync_changes` | checkpoint crescente + organização | feed incremental autoritativo |
| `audit_events` | UUID + organização | auditoria lógica append-only e redigida |
| `production_dispatch_authorizations` | `(OrganizationId, OperationId)` | autorização append-only de tentativa F12, fingerprint SHA-256 e cota UTC por organização |
| `oidc_*` | UUID | aplicações, autorizações e tokens OpenIddict |

Toda consulta de negócio recebe a organização do principal autenticado. DTOs de escrita não expõem `OrganizationId`.

## SQLite local

| Tabela | Finalidade |
|---|---|
| `cached_clients` | projeção local descartável |
| `offline_sync_operations` | comandos pendentes, tentativas e conflitos |
| `sync_state` | checkpoint confirmado |
| `catalog_cache` | snapshots JSON versionados de clientes/templates; descartáveis |
| `document_recognition_cache` | resultado reconhecido por SHA-256 e versão do motor; descartável, sem bytes/caminho do PDF |
| `document_reviews` | item validado por `(ScopeKey, DocumentId)`, com hash, chave semântica, período, findings e caminho estritamente local |
| `document_review_groups` | composição atual, política, revisão e snapshot de aprovação por `(ScopeKey, GroupId)` |
| `document_review_audit` | eventos locais append-only de importação, validação, override, agrupamento e aprovação |
| `processing_batches` | seleção individual/lote, modo, grupos, itens, ator e estado recuperável |
| `dispatch_items` | composição renderizada, destinatários original/efetivo, anexos, fingerprint, bloqueios e aprovação |
| `delivery_attempts` | tentativa persistida antes do provider, idempotency key única, estado e IDs Fake/Graph/Gmail |
| `dispatch_audit` | eventos append-only redigidos de composição, aprovação, provider, reconciliação e exportação |
| `workspace_preferences` | chave local única | pastas de entrada/acervo/relatórios, competência, lembrete de retenção e persistência da sessão de e-mail; sem token |
| `incidents` | `(ScopeKey, IncidentId)`; versão otimista | ocorrência vinculada a tentativa/lote/item/grupo, categoria, gravidade, estado, resumo redigido e resolução |
| `incident_audit` | `(ScopeKey, EventId)` append-only | abertura e transições do incidente com ator, instante, estados e nota redigida |

SQLite não contém access token, refresh token, senha ou bytes de PDF. O caminho absoluto passa a existir somente em `document_reviews`, porque a aplicação precisa reler e comparar o arquivo antes de aprovar; ele nunca é sincronizado. A revisão pode conter campos pessoais/financeiros e exige proteção do perfil do sistema; dados reais permanecem proibidos antes do hardening. Datas ordenáveis são armazenadas como inteiros UTC para comparação consistente no provider SQLite.

O PDF continua fora do SQLite: quando o acervo está configurado, seus bytes são copiados para uma pasta local `AAAA/MM`, e apenas o caminho/hash entram na revisão. `workspace_preferences` contém caminhos e opções, nunca conteúdo do arquivo ou credencial.

## Agregados locais da Fase 5

`ReviewDocument` conserva o reconhecimento original, cliente/estabelecimento, `DocumentPeriod`, chave semântica, estado, revisão e findings. `DocumentDispatchGroup` conserva política/versionamento, cliente, bucket temporal, membros e `GroupApprovalSnapshot`. O snapshot referencia cada documento por ID, hash e revisão e possui hash canônico próprio.

O escopo local vem da claim `organization_id`; eventos também registram o ator de `sub`. As stores substituem o estado operacional atual dentro de transações, mas somente acrescentam IDs de auditoria ainda inexistentes.

## Agregados locais da Fase 6

`ProcessingBatch` mantém seleção, modo e estado do lote. `DispatchItem` referencia um grupo aprovado e guarda o snapshot renderizado, rotas original/efetiva, hashes dos anexos, fingerprint e aprovação própria. `DeliveryAttempt` usa uma idempotency key única por escopo e é gravada como `Pending` antes do provider. `Pending` e `Ambiguous` sobrevivem ao reinício e só avançam por reconciliação.

No Fake, Test preserva destinatários originais e usa exclusivamente `example.invalid`. No Graph F7 e Gmail F8, qualquer modo preserva a rota original, mas usa exclusivamente o destinatário controlado. Assunto/corpo e e-mails ficam apenas no armazenamento local operacional; auditoria registra IDs, ações, códigos e resultados, nunca o corpo da mensagem ou credenciais.

## Extensão sem migration na Fase 7

A Fase 7 reutiliza `delivery_attempts.ProviderMessageId`/`ProviderDraftId` para IDs imutáveis Graph e mantém provider/fingerprint em cada tentativa. A chave local ganha número da tentativa; a propriedade Graph permanece estável por item/modo/fingerprint. Tokens não entram no SQLite.

O preflight de saída Graph (`Test`/`Send`) grava `audit_events` central com organização/usuário/dispositivo, OperationId, provider, contagem de anexos, versão, decisão e código redigido. Não grava destinatário, assunto, corpo, fingerprint nem ID/token Graph. Não foi necessária migration nova.

## Extensão sem migration na Fase 8

A Fase 8 reutiliza os mesmos campos `ProviderMessageId`/`ProviderDraftId` para IDs Gmail e registra `ProviderKey=google.gmail`. O `Message-Id` MIME estável é derivado de modo/item/fingerprint, mas não exige coluna nova. OAuth Google fica somente no `ISecretStore`; SQLite e PostgreSQL não armazenam token.

O preflight Gmail usa a mesma tabela/evento central redigido, com kill switch/versão próprios da Fase 8. Endereço, assunto, MIME, fingerprint, IDs e tokens Google não entram na auditoria. Não foi necessária migration nova.

## Concorrência e auditoria

`Version` começa em 1 e cada alteração exige `ExpectedVersion`. Divergência não faz last-write-wins: a API retorna HTTP 409. Alteração aplicada, checkpoint e auditoria redigida são confirmados na mesma transação. Modificar/excluir `AuditEvent` pelo DbContext é bloqueado. Dados sensíveis como CPF/CNPJ e e-mail não entram no JSON de auditoria.

## Extensão da Fase 9

Incidentes não possuem comando de exclusão. O store atualiza somente o registro corrente versionado e acrescenta eventos ainda inexistentes. A migration `AddPhase9Incidents` cria os índices de tentativa e estado/data. `WorkspacePreferences.RetentionReviewMonths` é apenas um lembrete de análise e não autoriza exclusão. O envelope `.fdmbackup` não é tabela: é arquivo externo versionado e autenticado, e seu conteúdo claro existe somente durante exportação/prévia de restauração.

## Extensão da Fase 12

`production_dispatch_authorizations` registra uma autorização antes da chamada externa com organização, operação, usuário, dispositivo, provider, fingerprint SHA-256, tamanho do lote, quantidade de anexos, versão e instante/data UTC. Não contém destinatário, assunto, corpo, nome de cliente, arquivo ou token.

A chave composta torna a repetição da mesma operação idempotente. Provider, fingerprint, lote e anexos precisam coincidir; divergência é recusada. O índice `(OrganizationId, AuthorizationDateUtc)` sustenta a cota diária e o índice com `UserId` permite investigação redigida. A entidade é append-only pelo DbContext. A migration `AddPhase12ProductionRollout` cria somente essa tabela e seus índices.
