# ADR-0008 — Segurança do piloto supervisionado

- Status: aceito para implementação da Fase 11; aceite operacional condicionado
- Data: 2026-08-21

## Contexto

A Fase 11 precisa permitir a validação do fluxo completo em poucas estações sem converter homologação em produção. O projeto já possuía kill switches de provedor e globais, porém o modo `Send` ainda podia aparecer, ser composto e chegar até uma trava posterior. Também faltavam um checklist recuperável, métricas agregadas e uma política explícita de staging consultável pelo suporte.

## Decisão

Adotar defesa em profundidade para o piloto:

1. o Desktop oferece somente `Test` e `Draft` quando `Phase11:Enabled=true` e `AllowSend=false`;
2. o workflow rejeita qualquer `Send` antes da composição e novamente antes da execução;
3. o preflight central recebe o modo da operação e aplica a política F11 além dos switches F7/F8/F10;
4. a política autenticada `GET /api/pilot/policy` falha fechada se ambiente, limite ou versão forem inválidos;
5. seis confirmações operacionais persistem por organização no SQLite, com histórico append-only de cada mudança;
6. prontidão considera apenas contagens agregadas: clientes, documentos, Test/Draft/Send, falhas/ambiguidade e ocorrências altas/críticas;
7. dados fictícios/anonimizados, máximo de cinco clientes, rollback e validação nas duas plataformas são condições do piloto.

## Consequências

- Um único erro de configuração não libera envio real.
- Desmarcar um controle preserva o evento anterior e revoga imediatamente a prontidão.
- Checklist “pronto” não autoriza produção, stable ou Send; apenas permite iniciar a execução supervisionada de homologação.
- Certificados, feed HTTPS, contas externas dedicadas e estações físicas continuam gates externos. Sem eles, o piloto permanece em preparação e nenhum resultado é declarado como testado.
- A Fase 12 deverá exigir uma decisão arquitetural e autorização humana próprias para qualquer abertura gradual de Send.
