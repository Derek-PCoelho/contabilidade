# ADR-0009 — Liberação gradual de produção

- Status: aceito para implementação da Fase 12; abertura operacional bloqueada
- Data: 2026-08-21

## Contexto

A Fase 12 prevê liberar `Send` por papel, monitorar a operação, exercitar backup/restauração e promover uma versão stable. O aceite operacional da Fase 11 ainda não ocorreu: não há staging HTTPS, beta assinada, certificados, estações físicas validadas nem evidência formal do piloto. Portanto, implementar F12 não pode significar declarar esses gates concluídos ou abrir envio real.

Também não é seguro depender somente dos switches históricos de Graph/Gmail e Fase 10. Uma configuração parcial poderia expor `Send`, lotes grandes ou uso por papel inadequado. A cota precisa ser central, por organização, e a repetição idempotente da mesma operação não pode consumir nova vaga nem autorizar conteúdo diferente.

## Decisão

Adotar uma política F12 adicional, obrigatória e fail-closed:

1. `Phase12:Enabled`, ambiente `production`, estágio `Limited|Gradual`, aceite do piloto, stable assinada, drill de restauração, monitoramento, resposta a incidentes e suporte devem estar todos confirmados;
2. `Phase11:Enabled` deve estar falso e os switches do provider, F10 e F12 precisam concordar para autorizar `Send`;
3. somente `Manager`, `Administrator` e `OwnerTechnical`, com permissão `email.send`, MFA e sessão ativa, podem receber autorização;
4. limites de 1–25 por lote e 1–500 por dia são validados; o default operacional é 5 por lote e 20 por organização/dia;
5. o preflight central usa transação serializável e uma autorização append-only por `(OrganizationId, OperationId)`; repetições idênticas são idempotentes e divergência de provider, fingerprint, lote ou anexos falha com conflito;
6. a interface remove `Send` enquanto a prontidão não estiver integralmente válida e mostra um painel somente leitura, sem botão local capaz de abrir produção;
7. `/api/production/policy` exige `email.send` + MFA e expõe apenas estágio, limites, contagens agregadas, papel da sessão e bloqueios não secretos;
8. configuração Production e verificador remoto nascem fechados. Nenhum deploy, OAuth ou Send é realizado pela implementação.

## Consequências

- Abrir um único switch não libera envio.
- Quota consumida representa autorização de tentativa, mesmo que o provider falhe; essa escolha é conservadora e evita tempestade de repetição.
- O fingerprint SHA-256 é persistido apenas na tabela de autorização para conferir idempotência; não entra no evento de auditoria funcional, junto com destinatários ou conteúdo.
- Falha central, concorrência serializável, versão antiga, papel inadequado, cota ou divergência idempotente bloqueiam antes do provider.
- `Limited` deve anteceder `Gradual`; aumento de limites exige nova decisão operacional e observação das métricas.
- A implementação de código pode ser concluída, mas produção e stable continuam bloqueadas até evidência humana dos gates externos descritos em `docs/PRODUCTION.md`.
