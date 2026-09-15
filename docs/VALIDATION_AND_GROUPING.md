# Validação e agrupamento — Fase 5, consolidada até 0.12.3

## Escopo

A Fase 5 transforma o reconhecimento local em itens revisáveis, sem criar mensagem, destinatário, rascunho ou envio. A aprovação desta fase confirma exclusivamente o conteúdo, a associação de cliente e a composição do grupo. Ela não é autorização de despacho.

O estado recuperável fica no SQLite local, separado por organização obtida do access token. PDFs e caminhos absolutos continuam locais e nunca são sincronizados ao servidor. Sem resolução autenticada do cliente, o item permanece bloqueado.

## Período contábil tipado

`DocumentPeriod` preserva a natureza temporal em vez de reduzir tudo a `MM/AAAA`:

| Tipo | Representação | Exemplo |
|---|---|---|
| `Monthly` | mês e ano | competência `08/2026` |
| `Annual` | ano | exercício `2026` |
| `AssessmentPeriod` | data ou intervalo de apuração | DARF `2026-08-31` |
| `DateRange` | início e fim | férias `01/09/2026 a 30/09/2026` |
| `EventDate` | data do evento | desligamento `20/08/2026` |
| `Unknown` | texto original | não normalizado com segurança |

Vencimento é armazenado separadamente. Para agrupamento mensal, uma apuração em agosto pode compartilhar o bucket `2026-08` sem perder seu tipo e valor originais.

### Correção humana de competência

`ReviewDocument.PeriodOverride` guarda uma correção explícita sem alterar os campos reconhecidos no PDF. Quando está preenchido, ele prevalece sobre a nova execução do parser; quando o operador restaura a competência reconhecida, volta a `null` e o período é recalculado a partir dos campos extraídos. A propriedade é opcional no JSON, de modo que workspaces de versões anteriores continuam legíveis.

Corrigir ou restaurar exige justificativa com o mínimo configurado. O serviço desanexa o documento do grupo anterior, incrementa a revisão, registra valor anterior/novo na auditoria, reexecuta regras e duplicidades e refaz o agrupamento. Uma aprovação anterior nunca sobrevive à mudança. Se a correção não informar vencimento, o vencimento já conhecido é preservado; competência idêntica ou período inválido é recusado.

## Regras configuráveis e severidade

Cada perfil determina versão, campos semânticos obrigatórios, necessidade de período/valor positivo, componentes da chave semântica e política de agrupamento. As regras implementam `IValidationRule<DocumentValidationContext>` e retornam `Info`, `Warning`, `Error` ou `Blocker`.

`Error` e `Blocker` não resolvidos impedem aprovação. As regras atuais conferem:

- existência, leitura, tamanho e SHA-256 atual do arquivo;
- tipo reconhecido e findings do parser;
- cliente resolvido sem bloqueio do resolvedor;
- perfil e campos semânticos obrigatórios;
- normalização de competência/período;
- total zero ou negativo inesperado, sem recalcular folha ou tributo;
- raízes CNPJ distintas entre empregadores;
- vencimento passado como `Warning` configurável.

O sistema não corrige PDF, não certifica obrigação jurídica e não tenta reproduzir cálculos do software contábil.

## Duplicidade

- exata: `ScopeKey da organização + SHA-256`;
- semântica: `ClientId + tipo + período canônico + componentes do perfil`;
- férias, rescisão, 13º e pró-labore acrescentam o identificador do trabalhador/sócio quando aplicável.

A primeira ocorrência permanece revisável. Reimportações exatas ou semanticamente equivalentes recebem estado `Duplicate`, finding bloqueante, referência local ao original e nenhum `GroupId`. Portanto, não entram na aprovação individual nem em `Aprovar todos os elegíveis`.

## Agrupamento

A chave automática combina cliente, estabelecimento quando exigido, bucket temporal, código e versão da política. Folha, FGTS, arrecadação, 13º e pró-labore usam a política mensal compatível; férias e rescisões formam eventos individuais. A política continua configurável por perfil.

O operador pode:

- separar parte não vazia de um grupo;
- unir grupos do mesmo cliente e período;
- aprovar um grupo pronto;
- aprovar todos os grupos elegíveis.

Merge entre clientes ou períodos diferentes falha fechado. Split, merge, entrada/saída de documento, mudança de cliente, mudança de hash ou de findings revogam o snapshot anterior e exigem nova aprovação.

### Retirada da revisão e grupos vazios

**Retirar da revisão** remove o `ReviewDocument` somente do workspace ativo. Se ele pertencia a um grupo, o serviço primeiro o desanexa e invalida o snapshot; depois registra `document.removed_from_review` com evidência técnica e justificativa. Essa operação não apaga, move nem sobrescreve o PDF físico, portanto exclusão do arquivo continua sendo uma decisão separada de acervo/suporte.

Depois de retirada, correção ou revalidação, grupos sem qualquer documento ativo são removidos e recebem o evento `group.empty_removed`. A mesma limpeza ocorre durante `LoadAsync`, eliminando grupos vazios persistidos por versões anteriores sem transformar documentos bloqueados em aprovados.

## Override de cliente

O override aceita somente uma alternativa que já veio do resolvedor autenticado para o documento. Não é possível fornecer um `ClientId` arbitrário. A justificativa tem no mínimo dez caracteres; usuário, timestamp, valor anterior/novo mascarado e correlação entram na auditoria local. Depois da alteração, o documento sai do grupo anterior, a aprovação é revogada e todas as regras/duplicidades são executadas novamente.

## Snapshot de aprovação

O snapshot contém:

- identidade e revisão do grupo;
- ator e instante UTC;
- `DocumentId`, SHA-256 e revisão de cada documento;
- cliente, estabelecimento, período canônico e chave semântica;
- hash canônico do conjunto ordenado.

Qualquer divergência entre o estado atual e esse conteúdo elimina o snapshot. Recipients, assunto, corpo, `DispatchFingerprint` e estados de provider pertencem às Fases 6–8 e não existem nesta implementação.

Na interface 0.12.3, um grupo aprovado é apresentado apenas como **Aprovado no aplicativo**. O identificador técnico do ator permanece na auditoria estruturada para suporte e rastreabilidade, mas não aparece na jornada comum nem é interpretado como identidade do cliente ou confirmação de envio.

## Persistência e auditoria

As tabelas `document_reviews`, `document_review_groups` e `document_review_audit` são transacionadas no SQLite. Documentos/grupos representam o estado recuperável atual; eventos são acrescentados sem exclusão pela store. No perfil conectado, o `ScopeKey` vem da claim `organization_id` e o ator vem de `sub`; no perfil local, ambos são identificadores isolados e estáveis fornecidos por `LocalDesktopOperationContextAccessor`.

O caminho absoluto é necessário apenas para revalidar o arquivo local e não sai do dispositivo. Esta persistência aumenta a necessidade de criptografia/controle do perfil do sistema antes de dados reais, risco já mantido aberto para hardening.
