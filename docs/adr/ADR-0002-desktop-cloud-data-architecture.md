# ADR-0002: Arquitetura desktop + backend central

- Status: Proposed
- Data: 2026-08-20
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O processamento dos documentos deve continuar local, mas cadastros, permissões, regras e auditoria precisam ser compartilhados entre máquinas. Caminhos locais e PDFs reais não podem ser tratados como dados centrais do MVP. O produto também precisa recuperar lotes após falhas e suportar operação degradada.

## Decisão proposta

- O Desktop Avalonia processa arquivos localmente e mantém SQLite como cache, índice, fila offline e estado recuperável do lote.
- ASP.NET Core Web API é a única porta de acesso ao backend; o Desktop nunca acessa PostgreSQL diretamente.
- PostgreSQL é a fonte de verdade para organização, usuários/RBAC, clientes, estabelecimentos, destinatários, templates, perfis publicados, feature flags e auditoria/metadados centrais.
- SignalR apenas notifica mudanças; o cliente busca a versão autoritativa por API.
- Toda entidade compartilhada recebe `OrganizationId`; no servidor esse valor é derivado do contexto autenticado, não aceito como autorização vinda do cliente.
- Sincronização usa checkpoint incremental, optimistic concurrency, idempotency keys, backoff com jitter e conflitos explícitos.
- PDFs reais permanecem locais no MVP. O servidor recebe apenas metadados mínimos/redigidos; caminhos absolutos locais nunca são sincronizados.
- Importação e análise podem funcionar offline. Envio real fica bloqueado quando a auditoria central ou os controles remotos não puderem ser confirmados.

## Fronteiras de dados

| Dado | Local | Central |
|---|---:|---:|
| PDF real e preview temporário | Sim | Não no MVP |
| Caminho absoluto | Sim | Não |
| Estado recuperável do lote/fila offline | Sim | Metadados necessários |
| Clientes, estabelecimentos e destinatários | Cache | Fonte de verdade |
| Usuários, papéis e feature flags | Cache mínimo | Fonte de verdade |
| Eventos de auditoria | Fila durável | Registro central append-only lógico |
| Token de e-mail | Cofre do SO | Não em texto claro/não sincronizado |

## Consequências

- A operação local continua responsiva e recuperável.
- A sincronização e a concorrência tornam-se responsabilidades explícitas, com maior custo de implementação e testes.
- O backend precisa de backup/restore, isolamento por organização, health checks e migrações controladas antes de dados reais.
- Um futuro storage central de PDFs exige novo ADR de LGPD, retenção, criptografia e gestão de chaves.

## Gate para Accepted

Na Fase 2, testes devem demonstrar dois clientes simulados sincronizando, conflito crítico visível, deduplicação de comando e bloqueio seguro de Send quando a auditoria central está indisponível.

## Evidência da Fase 2

- dois clientes SQLite sincronizam pela abstração de transporte contra o repositório central;
- conflito por versão, deduplicação por operação e isolamento por organização passam em testes;
- migration central passa em PostgreSQL 18 real e a fila local sobrevive offline;
- não existe qualquer comando ou provider de Send, portanto a ausência do backend não pode degradar para envio.

## Evidência adicional das Fases 6–8

- a máquina de estados persiste `Pending` antes do provider e bloqueia retry de `Pending/Ambiguous`;
- toda saída Graph/Gmail (`Test`/`Send`) exige preflight central autenticado com `email.send`, MFA, sessão ativa, kill switch e versão mínima próprios do provider;
- o preflight grava auditoria central redigida antes do provider; API/banco/controle indisponível bloqueia sem criar tentativa ou chamar Graph/Gmail;
- testes confirmam negativa remota e indisponibilidade fail-closed.

O ADR permanece `Proposed` até os smokes com backend e contas dedicadas Microsoft/Google confirmarem o comportamento completo em macOS e Windows; o requisito de desenho para bloqueio seguro já possui cobertura automatizada.

## Alternativas consideradas

- Aplicativo apenas local: rejeitado por não sincronizar cadastros e auditoria.
- Desktop conectado diretamente ao PostgreSQL: rejeitado por segurança e acoplamento.
- Enviar todos os PDFs ao backend no MVP: rejeitado por minimização, custo e risco LGPD.
