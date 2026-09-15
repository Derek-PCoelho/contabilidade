# Arquitetura — Fase 12 com manutenção candidata pós-Fase 12 0.12.7

## Visão

O Desktop Avalonia possui dois perfis explícitos. Sem `Phase2:ApiBaseAddress`, inicia no perfil local padrão: SQLite é a autoridade do catálogo daquela instalação e também resolve os clientes dos documentos. Com uma URI de API configurada, inicia no perfil conectado: o servidor é a fronteira de autorização, PostgreSQL é a fonte compartilhada e o Desktop acessa dados centrais somente pela API. Em ambos, o PDF é aberto, interpretado, validado e agrupado localmente; o perfil conectado envia ao endpoint de resolução apenas identificadores/nome elegíveis e evidência mascarada.

```text
Desktop Avalonia
  ├─ perfil de dados na inicialização
  │    ├─ local (padrão) ── catálogo + resolvedor SQLite desta instalação
  │    └─ conectado (explícito) ── catálogo + resolvedor HTTP/API/PostgreSQL
  ├─ contexto operacional ── permissões locais mínimas ou organização/ator/permissões do JWT
  ├─ navegador do sistema ── OIDC Authorization Code + PKCE
  ├─ PDF local ── MIME → SHA-256 → PdfPig → perfil → semantic roles
  ├─ regras ── período tipado → findings → duplicidade → grupos → snapshot
  ├─ workflow ── composição → aprovação → tentativa persistida → Fake/Graph/Gmail
  ├─ Graph opt-in ── MSAL → draft imutável → anexos → send → reconcile
  ├─ Gmail opt-in ── OAuth+PKCE → MIME → draft → send → reconcile
  ├─ cofre nativo ── Keychain macOS / DPAPI CurrentUser Windows
  ├─ relatórios locais ── XLSX + PDF + CSV pela interseção cliente/período
  ├─ incidentes locais ── tentativa → contenção/apuração → resolução + auditoria append-only
  ├─ backup do catálogo ── JSON interno → PBKDF2 → AES-256-GCM → .fdmbackup
  ├─ espaço operacional ── competência ativa + entrada/acervo AAAA/MM + saída de relatórios
  ├─ atualização manual ── canal → verificar → baixar/conferir → reiniciar/aplicar
  ├─ piloto ── política staging → checklist por organização → métricas agregadas → rollback
  ├─ produção gradual ── prontidão → papel/MFA → lote/cota → preflight idempotente
  ├─ SQLite: catálogo local ou cache + checkpoint/fila + revisão/workflow (sem tokens/PDF)
  └─ HTTPS API + SignalR no perfil conectado ── somente campos elegíveis e evidência mascarada
                  │
ASP.NET Core API  ├─ Identity + OpenIddict + RBAC + 2FA + rate limits particionados
                  ├─ tenant derivado dos claims autenticados
                  └─ EF Core/Npgsql ── PostgreSQL
```

## Projetos e dependências

| Projeto | Responsabilidade na Fase 12 |
|---|---|
| `Domain` | cliente PF/PJ, documentos brasileiros, identificadores, estabelecimentos, destinatários, templates e concorrência |
| `Contracts` | DTOs do catálogo, reconhecimento, revisão, lotes, composição, tentativas, piloto, produção e auditoria sem tenant fornecido como autorização |
| `Application` | reconhecimento, regras, agrupamento, fluxo de envio, incidentes, idempotência, reconciliação, prontidão do piloto/produção, proteção e update |
| `Infrastructure` | PdfPig, catálogo/resolvedor SQLite local, clientes HTTP conectados, contextos operacionais local/JWT, checklist do piloto, incidentes, proteção/redação, providers, Keychain/DPAPI, relatórios, OIDC/SignalR e Velopack |
| `Desktop` | jornadas, seleção de competência, feedback cadastral/importação, atualização, incidentes, backup/restore, retenção, acessibilidade, execução e relatórios; controles técnicos fechados ficam fora da jornada comum |
| `Server` | resolução tenant-safe, CRUD, OIDC, RBAC/MFA, rate limits, políticas de release/piloto/produção/Send, cota, auditoria e health checks |

Domain e Application não dependem de Avalonia, EF, PDF ou provedores de e-mail. Desktop não referencia Server e nunca recebe uma connection string PostgreSQL.

## Apresentação e navegação do Desktop

O Desktop expõe uma navegação lateral orientada às jornadas Início, Clientes, Documentos, Envios, Relatórios, Histórico e Configurações. O cadastro de cliente é contínuo; estruturas necessárias ao domínio, mas excepcionais no cotidiano — filiais, aliases/códigos e mensagens específicas — usam revelação progressiva. Fases, providers, códigos internos e cenários fake ficam restritos aos detalhes de suporte. Na correção 0.12.1, o formulário passou a validar e confirmar o salvamento no próprio contexto, duplicou a ação Salvar no fim do fluxo, permitiu associar o e-mail opcional do representante ao contato de entrega e removeu booleanos técnicos da apresentação.

Na correção 0.12.2, criação e atualização receberam ações distintas, clientes inativos permanecem localizáveis/reativáveis e duplicidade de CPF/CNPJ inativo recupera o registro existente. O editor de modelos mostra contadores, aceita corpo de até 20.000 caracteres e insere somente placeholders suportados por rótulos amigáveis. Documentos exibe a causa do bloqueio e a próxima ação; Envios condiciona comandos ao estado selecionado; cartões, estados vazios, detalhes progressivos e dimensões mínimas reduzem corte e ruído em janelas menores.

Na correção 0.12.3, o estado ativo/inativo e o arquivamento lógico foram separados da mutação integral do agregado. Salvar ou mudar o estado no catálogo local participa da mesma transação SQLite que a revalidação documental; falha nessa etapa reverte cadastro, auditoria e invalidação de aprovação. Isso permite recuperar cadastros legados para correção sem afrouxar novos salvamentos: e-mail ativo incompleto bloqueia prontidão, enquanto contato legado inválido já inativo é retirado da avaliação/mutação compatível. CPF de sócio/representante não integra identificadores elegíveis de resolução. A interface abre clientes por seleção direta, oferece máscara fiscal local, modelos padrão PJ/PF e mantém ações destrutivas recuperáveis fora do fluxo principal.

Na correção focal 0.12.4, Clientes mantém lista e editor como regiões recolhíveis coordenadas: uma delas sempre permanece útil, e criar/abrir direciona o foco ao editor. Documentos passou a ter uma única rolagem externa e uma jornada primária **Adicionar → Conferir → Liberar**; listas paralelas de grupos deixaram o fluxo comum. A apresentação deriva a identificação e o resumo de organização do estado já calculado pelo resolvedor e pelo agrupador — cliente, documento fiscal mascarado, método, estabelecimento e competência — sem alterar as regras contábeis. Operações manuais de separar/unir e evidências permanecem em revelação progressiva; resultado não resolvido nunca é apresentado como associação automática.

Na correção 0.12.5, **conjunto para mensagem** passou a ser a expressão apresentada para a unidade técnica de agrupamento: documentos do mesmo cliente, estabelecimento, período e código/versão da política que podem seguir juntos em uma mensagem. Documentos permite liberar somente o conjunto selecionado, todos os conjuntos prontos do cliente selecionado ou todos os conjuntos prontos do recorte, sempre excluindo erros, bloqueios e duplicidades. Correção/retirada, organização manual e evidências de identificação iniciam recolhidas e permanecem fora do caminho principal.

Na candidata 0.12.6, a seleção individual ganhou um visualizador do conjunto completo, mas não alterou a unidade contábil: cliente, estabelecimento, competência e código/versão da política diferentes continuam produzindo conjuntos distintos. A ação explícita **Liberar este cliente** aceita todos os IDs prontos do mesmo cliente e pode atravessar competências, porém valida o conjunto exato antes de qualquer escrita e mantém uma composição/mensagem independente por grupo. Seleção incompleta, cliente misto ou qualquer conjunto solicitado que esteja ou fique bloqueado, com erro, duplicado ou invalidado concorrentemente aborta a operação sem persistência parcial; outros conjuntos bloqueados do cliente ficam fora da solicitação. A liberação geral conserva a fronteira mensal.

A organização manual permanece recolhida e foi dividida por intenção. **Separar** remove somente o documento selecionado para um novo conjunto; **unir** oferece somente conjuntos com o mesmo cliente, estabelecimento, competência e código/versão da política. Ambas exigem motivo, produzem auditoria e revogam os snapshots afetados antes de nova validação. A interface não oferece combinações incompatíveis como uma operação aparentemente válida. O ADR-0011 formaliza estas invariantes e sua relação com o ADR-0006.

Envios apresenta uma sequência vertical única de quatro passos — **Preparar → Conferir destinatário, texto e anexos → Aprovar → Concluir** — e mantém o modo escolhido imutável depois da preparação; mudar Teste/Rascunho/Send exige uma nova composição. A jornada comum projeta uma **fila atual**: para cada `GroupId`, exibe somente a versão mais recente e não cancelada, enquanto versões substituídas continuam preservadas no workspace e na auditoria. Referência curta, cliente, competência e anexos sustentam busca/filtros e distinguem novas mensagens do mesmo cliente. Preparação, aprovação e conclusão restauram a seleção pela identidade da nova mensagem, evitando regressão visual de etapa; o retorno de Envios para Documentos realinha o conjunto ao documento corrente. Rótulos, tipos documentais e conteúdo visível usam português. No perfil local, a conclusão continua sendo simulação honesta: não chama serviço externo, não envia mensagem e não comprova chegada.

Relatórios usa dois filtros ortogonais. O primeiro seleciona todos os clientes ou um cliente; o segundo seleciona todos os períodos, um mês, um ano ou um intervalo de competências mensais, como `08/2026 a 12/2026`. A consulta aplica a interseção entre cliente e período aos documentos e às comunicações correntes e produz um pacote coerente em XLSX, PDF e CSV; uma tentativa antiga de versão substituída permanece auditável, mas não integra o relatório corrente. XLSX/PDF declaram a cobertura efetiva; o PDF relaciona tipos e nomes de todos os documentos do recorte, inclusive os que ainda não possuem mensagem, para tornar a evidência interpretável. As visões incluem pendências documentais e o resultado conhecido de cada comunicação. `AcceptedByProvider` continua significando somente aceitação técnica pelo serviço; sem evidência específica do provider, entrega ao destinatário permanece **não comprovada**.

Histórico projeta eventos técnicos em linhas operacionais com cliente, competência, tipos e quantidade documental quando o vínculo persistido disponibiliza esse contexto. Eventos agregados são projetados em cada competência registrada, e a retirada conserva cliente, período e tipo na descrição. Eventos antigos sem referência suficiente declaram a indisponibilidade em vez de inventar cliente ou usar um nome de arquivo sem contexto como explicação. A limpeza permanece apenas visual e admite tudo, competência, dia, uma hora cheia ou intervalo informado. Os intervalos ocultos são recuperáveis e não alteram nem removem os eventos originais: auditoria funcional continua append-only e permanece disponível para restauração da visualização e para as evidências autorizadas.

Na manutenção 0.12.7, o inventário de Relatórios passa a unir duas fontes sem perder identidade: documentos da revisão corrente e snapshots imutáveis dos anexos de cada mensagem. A chave é `DocumentId`; quando as duas fontes contêm o item, vence o estado corrente, e quando o item existe somente no snapshot ele continua no XLSX/PDF/CSV. Isso corrige o join temporal sem fabricar documento ausente: a base local BOREAL observada contém dois PDFs persistidos, enquanto o caso de três anexos é deliberadamente sintético e mantém um deles apenas no snapshot.

O índice de Histórico recebe critérios ortogonais de tempo — dia, faixa horária, intervalo, mês e ano —, cliente, documento, competência e texto. Um evento que já possua `DocumentId` nunca expande para todos os documentos que hoje pertençam ao antigo grupo: usa a identidade original e os metadados persistidos, inclusive após retirada. A limpeza de “tudo que aparece agora” registra somente os IDs da projeção visível, continua restaurável e não altera a auditoria append-only. A auditoria cadastral mantém armazenamento e retenção próprios, mas sua projeção pode trocar de cliente no contexto do Histórico.

O CPF opcional de sócio/representante é validado antes da mutação do agregado e produz erro orientado ao campo; ele permanece fora dos identificadores elegíveis de resolução documental. O valor padrão de `{{escritorio.nome}}` é **AL Contadores Associados**. Essas mudanças, o cartão de Competência e a reorganização visual não criam dependência nova nem exigem ADR adicional; permanecem alinhadas ao ADR-0011.

Ano/mês formam um recorte operacional global e idempotente: a opção é construída uma única vez por valor, persiste no SQLite e não é substituída ao trocar de área ou importar um lote com competências distintas. A seleção filtra documentos, grupos, mensagens, histórico e lotes; em Relatórios, ela pode iniciar a escolha, mas os filtros próprios e explícitos definem a exportação. Selecionar um mês com “Todos os anos” mantém o mês como filtro efetivo. O recorte não reduz `DocumentPeriod` a `MM/AAAA`. Na importação, o período reconhecido define o destino `AAAA/MM`; lotes com múltiplas competências apenas informam itens fora do filtro. A pasta de entrada é somente fonte e a pasta de relatórios recebe subpastas do mesmo recorte. Preferências não são autoridade de tenant. O cartão de Competência usa duas linhas responsivas — identificação/período e seletores equivalentes de ano/mês — para não acoplar a regra global à largura da janela.

O perfil local admite uma única instância do Desktop por usuário, por bloqueio de arquivo mantido durante todo o processo. Isso é a barreira de concorrência entre processos para os workspaces SQLite que são persistidos como unidade. Na importação, a cópia no acervo é staging recuperável: a coleção visual só muda depois do commit e uma cópia nova é removida se o commit falhar.

O retrato, a fotografia da equipe e a marca institucional autorizados são recursos oficiais do projeto Desktop, versionados em `Assets/Branding` e incorporados aos builds Avalonia para Windows e macOS. Eles não atravessam Application/Domain nem são tratados como dados operacionais ou sincronizados. Origem, autorização e hashes estão em `docs/BRAND_ASSETS.md`; composição visual em `docs/UX_AND_BRANDING.md`.

## Pipeline de reconhecimento

1. validar caminho, extensão, tamanho e assinatura MIME real;
2. calcular SHA-256 em streaming e consultar cache pela versão do motor;
3. extrair texto/palavras por página, com limites e cancelamento;
4. texto vazio retorna `NeedsOcr`, sem OCR automático;
5. classificar por âncoras combinadas e executar o perfil específico;
6. atribuir papéis semânticos e evidência de página/posição;
7. enviar ao resolvedor apenas papéis elegíveis; no perfil conectado, a evidência segue mascarada pela API;
8. resolver no catálogo SQLite desta instalação ou dentro da organização autenticada no backend, sem aceitar tenant vindo do DTO;
9. calcular confiança e guardar o resultado local por hash para revisão humana.

Cada valor é lido dentro do rótulo contextual do perfil; não existe regex global que escolha a primeira inscrição como cliente.

## Pipeline de validação e aprovação

1. reler o caminho local e comparar SHA-256 com o reconhecimento;
2. construir `DocumentPeriod` sem perder a semântica de competência, apuração, intervalo ou evento; quando houver correção humana válida, `PeriodOverride` prevalece até ser explicitamente restaurado;
3. executar regras universais e do perfil com quatro severidades;
4. calcular duplicidade exata e semântica dentro do escopo da organização;
5. anexar somente itens elegíveis a grupos configuráveis;
6. separar somente o documento indicado; aceitar merge somente entre conjuntos com o mesmo cliente, estabelecimento, competência e código/versão da política; exigir motivo, auditar a alteração e invalidar aprovações afetadas;
7. aprovar somente conjuntos sem `Error`, `Blocker` ou duplicidade: conjunto individual; seleção exata e atômica de todos os aprováveis do mesmo cliente, mesmo entre competências; ou todos os prontos de um único mês. IDs incompletos/mistos/inválidos ou conjunto solicitado que deixe de ser aprovável falham antes da persistência; conjuntos bloqueados não solicitados ficam fora da ação por cliente;
8. persistir snapshot canônico de hashes/revisões e revogá-lo após qualquer mudança;
9. ao corrigir/restaurar competência, mudar cliente ou retirar um item, desanexar, revalidar, recalcular duplicidade/agrupamento e remover grupos vazios antes de oferecer nova aprovação.

No perfil conectado, o `ScopeKey` da revisão é extraído da claim autenticada `organization_id`; no perfil local, é o escopo fixo e isolado desta instalação. Não existe campo de UI para escolher tenant. O snapshot aprova conteúdo e agrupamento, não envio. A retirada do workspace não apaga o PDF físico. Veja `docs/VALIDATION_AND_GROUPING.md`.

## Pipeline de composição e execução

1. obter o contexto operacional do perfil ativo e aceitar somente grupos com snapshot documental válido;
2. obter cliente, destinatários e templates versionados da autoridade do perfil ativo — SQLite local ou catálogo central pela API;
3. selecionar rotas por regras explícitas, sem IA, e bloquear endereço/perfil incompatível;
4. renderizar placeholders permitidos, produzir texto + HTML codificado e calcular fingerprint canônico; o modo de operação integra esse retrato e fica imutável após a preparação;
5. no modo Test, substituir o destino efetivo por `@example.invalid` e preservar a rota original no snapshot;
6. exigir aprovação separada da composição; qualquer mudança de arquivo, grupo, destinatário ou template a revoga;
7. persistir `DeliveryAttempt(Pending)` antes da chamada e salvar o resultado assim que conhecido;
8. usar `fake.local` por padrão ou `microsoft.graph`/`google.gmail` somente quando configurados; timeout/ambiguidade exigem `Reconcile`, nunca retry cego;
9. registrar auditoria redigida e exportar visões consistentes em XLSX/PDF/CSV pelo filtro combinado cliente/período, distinguindo ausência de mensagem, pendência documental, simulação, aceite técnico e entrega não comprovada.

No Graph da Fase 7, o compositor força o destinatário controlado. Antes de qualquer saída (`Test` ou `Send`), a API central autoriza por RBAC/MFA/sessão/kill switch/versão e persiste preflight redigido; indisponibilidade ou resposta incoerente bloqueia. Só então o adaptador cria/reaproveita draft identificado por propriedade estendida, valida/anexa os PDFs e envia o ID imutável. HTTP 202 permanece `AcceptedByProvider`, nunca prova de entrega. Retry automático é restrito a operações idempotentes; um draft reconciliado como não enviado exige nova confirmação humana.

No Gmail da Fase 8, a sessão usa navegador do sistema, loopback aleatório e PKCE, guardando refresh token exclusivamente no cofre nativo. O compositor também força destino controlado e o mesmo preflight central, com kill switch próprio, precede Test/Send. MimeKit produz MIME UTF-8; um `Message-Id` estável permite localizar/reaproveitar draft pelo escopo mínimo `gmail.compose`. O adaptador envia o ID do draft. Se a resposta do Send se perde e o draft desaparece, o resultado continua ambíguo: não há leitura ampla da caixa nem inferência de entrega.

## Perfis de dados do Desktop

O perfil é definido uma única vez no composition root:

| Perfil | Seleção | Autoridade cadastral | Contexto operacional | Comportamento diante de falha remota |
|---|---|---|---|---|
| Local (padrão) | `Phase2:ApiBaseAddress` ausente | registros `local-*` no SQLite da instalação | `LocalDesktopOperationContextAccessor`: escopo/ator locais e somente `documents.process`, `batch.approve`, `email.draft`, `audit.export` | cadastro, resolução, revisão, rascunho e relatório local não dependem da API; `email.send` nunca é concedido |
| Conectado | `Phase2:ApiBaseAddress` com URI absoluta | PostgreSQL, acessado somente pela API | `JwtDocumentReviewContextAccessor`: organização, ator e permissões derivados do token | pode ler o último cache central em falha transitória conforme ADR-0002, mas nunca grava no catálogo autoritativo local; sem token, o despacho tem zero permissões |

Não existe merge, fallback de escrita ou promoção automática entre as autoridades. Os registros `local-client`, `local-message-template` e `local-catalog-audit` não compartilham chaves com o cache central `client`/`message-template`. Alterar o endereço exige reiniciar o Desktop. A cópia protegida pode transportar clientes e modelos com prévia e confirmação, mas não substitui uma estratégia futura de sincronização/identidade organizacional. O ADR-0010 registra a decisão; o ADR-0002 permanece normativo para o perfil conectado.

O `LocalDesktopOperationContextAccessor` implementa tanto `IDocumentReviewContextAccessor` quanto `IDispatchExecutionContextAccessor`; assim, o mesmo scope separa revisão e workflow sem fingir um JWT local. `email.draft` permite preparar rascunho no provider local, e `audit.export` permite produzir os relatórios locais já autorizados; nenhuma delas equivale a OAuth ou permite `Send`. No perfil conectado, token ausente ou inválido produz o scope técnico isolado `unauthenticated-connected` e conjunto vazio de permissões, impedindo que a ausência de login herde os poderes do perfil local.

## Fluxo de sincronização do perfil conectado

1. uma alteração local entra na fila SQLite com `OperationId` e `ExpectedVersion`;
2. `push` autenticado aplica lote de até 100 comandos em transação serializável;
3. PostgreSQL deduplica por `(OrganizationId, OperationId)`;
4. versão obsoleta retorna conflito explícito e preserva o valor autoritativo;
5. evento append-only redigido e checkpoint são gravados na mesma transação;
6. SignalR notifica apenas o grupo da organização;
7. cada cliente executa `pull` incremental e avança seu checkpoint local.

Falha de rede mantém a operação enfileirada, com backoff exponencial e jitter determinístico. SignalR não transporta o registro autoritativo. Este fluxo não é executado como uma fusão implícita do catálogo do perfil local.

## Fluxo de atualização e distribuição

`VelopackApp` executa como primeira ação do processo. A UI depende apenas de `IAppUpdateService` e separa verificar, baixar e reiniciar; troca de canal invalida o estado pendente. Infrastructure resolve o feed em quatro canais (`osx-arm64`/`win-x64` × `beta`/`stable`), recusa downgrade e traduz erro de integridade em falha fechada.

O código e preferências ficam fora do diretório instalado, permitindo reparação sem apagar o acervo. O Server expõe política autenticada de versão mínima e switches de canal/Send; o preflight de e-mail aplica o máximo entre a versão global e a do provider. O repositório privado não é feed porque o Desktop não pode conter token pessoal. Assinatura/notarização, publicação e promoção usam workflow manual protegido. Veja `docs/RELEASES.md` e ADR-0004.

## Identidade e autorização

- ASP.NET Core Identity armazena usuários, papéis, lockout e TOTP;
- OpenIddict emite tokens por Authorization Code + PKCE para public client;
- access token dura 10 minutos e refresh token 30 dias, com armazenamento/revogação no servidor;
- papéis privilegiados exigem TOTP no login e claim `amr=mfa` nas políticas de escrita/gestão;
- toda política também valida sessão de dispositivo não revogada;
- modo de autenticação por headers existe somente em `Development`/`Testing`, exige opt-in e fora de `Testing` aceita apenas loopback;
- produção recusa wildcard em `AllowedHosts` e exige certificados PFX separados, com chave privada e validade superior a 30 dias;
- autenticação precede o rate limiter para permitir partição por usuário; origem da conexão é fallback sem entrar em logs funcionais.

## Fronteira do piloto supervisionado

`PilotModeOptions` é uma política local explícita. Quando ativa, a UI enumera apenas os modos autorizados. O `DispatchWorkflowService` repete a decisão antes da composição e da execução, e o preflight central recebe também `OperationMode`; assim, `Phase11:AllowSend=false` vence mesmo se um switch antigo de provider ou da Fase 10 for aberto acidentalmente.

O checklist usa a tabela genérica `workspace_preferences`, com chave derivada por SHA-256 do `ScopeKey`; o payload conserva o escopo e o serviço normaliza os seis itens conhecidos. Cada alteração adiciona evento próprio e nunca reescreve o histórico. O estado não sincroniza dados pessoais e não é uma autoridade para liberar Send.

As métricas são calculadas no Desktop a partir dos workspaces já isolados: somente contagens de clientes distintos, documentos, estados, modos, falhas/ambiguidade e ocorrências altas/críticas. Prontidão exige configuração staging segura, máximo de cinco clientes, zero tentativa Send, zero pendência técnica grave e todas as confirmações humanas. O endpoint autenticado `/api/pilot/policy` expõe somente switches não secretos e falha com 503 diante de ambiente, limite ou versão inválidos. Veja ADR-0008 e `docs/PILOT.md`.

## Fronteira da produção gradual

`ProductionReadinessEvaluator` combina F12 com piloto desativado/aceito, kill switch global e canal stable. O Desktop recebe o snapshot pronto no composition root e não possui comando para alterar o estado: enquanto houver bloqueador, `Send` não integra a jornada. O workflow repete a decisão antes da composição e da execução para operações externas.

No servidor, o preflight de `Send` exige a política `email.send` — permissão, MFA e sessão ativa —, cruza os papéis privilegiados configurados, a maior versão mínima, o limite por lote e a cota diária da organização. A transação serializável cria `production_dispatch_authorizations` antes do provider, com chave `(OrganizationId, OperationId)`. O fingerprint SHA-256 permite repetição idempotente sem armazenar conteúdo; qualquer divergência da mesma operação falha fechada. A entidade é append-only e a política expõe somente agregados.

F7/F8, F10 e F12 continuam independentes: todos precisam concordar. `Closed` é o estado versionado, e a passagem a `Limited`/`Gradual` depende de infraestrutura e aprovação humanas fora do aplicativo. Veja ADR-0009 e `docs/PRODUCTION.md`.

## Cadastro e autoridade por perfil

- `Client` é o agregado de PF/PJ e controla a versão de seus identificadores, estabelecimentos, destinatários e sócios/representantes;
- o perfil local persiste cliente, modelos e auditoria redigida no SQLite; o perfil conectado persiste pela API no PostgreSQL;
- sócios/representantes pertencem a uma coleção separada; CPF de sócio não é identificador de resolução da empresa;
- CPF/CNPJ passam por normalização restritiva e validação matemática; estabelecimento PJ deve compartilhar a raiz do CNPJ;
- destinatário possui papel explícito `To`, `Cc` ou `InternalCopy`; endereço secundário nunca é inferido como cópia;
- remoção de item do agregado o inativa no histórico; clientes não são apagados fisicamente;
- cliente inativo ou sem destinatário `To` válido recebe código de bloqueio operacional explícito;
- modelos de mensagem são versionados e armazenados, mas a Fase 3 não contém provider nem comando de envio;
- import/export usa JSON `FormatVersion=1`; importação suporta simulação (`DryRun`) e sobrescrita explícita;
- toda escrita grava mudança e auditoria redigida; somente a escrita central emite aviso SignalR dentro da organização autenticada.

## Verificação da candidata 0.12.6

A arquitetura acima passou por restore bloqueado, formatação, build Release com 0 warnings/0 erros, **296 testes aprovados** — 29 Domain, 80 Application, 73 Infrastructure, 33 Server e 81 UI — e auditoria transitiva sem pacotes vulneráveis. O XLSX foi inspecionado com `artifact_tool`, sem erro de fórmula e com a contagem correta de conjuntos; o PDF A4 de duas páginas foi renderizado e inspecionado.

O pacote macOS arm64 `validation` foi gerado com checksums íntegros e instalado em `/Applications/Folhas da Michelly.app`; os hashes do apphost e dos assemblies principais coincidem com o pacote, a versão de arquivo é `0.12.6.0` e a assinatura é ad-hoc. O publish `win-x64` foi gerado como PE32+. A CI 0.12.6 aprovou Ubuntu/PostgreSQL, macOS ARM64 e Windows x64 no commit `27121f7`. Essas evidências verificam arquitetura/artefatos, mas não equivalem a assinatura/notarização, pacote Windows assinado, smoke físico Windows, staging, produção, OAuth ou envio real. O smoke visual final no aplicativo macOS instalado permanece pendente porque o Mac está bloqueado.

## Verificação planejada da candidata 0.12.7

O fechamento esperado, ainda a confirmar, é restore bloqueado, formatação, build Release, **327 testes** — 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI —, auditoria transitiva e inspeção visual integral do XLSX/PDF. Empacotamento/instalação macOS, publish Windows, CI e smoke visual devem ser registrados separadamente. Nenhum teste default abre OAuth, chama Graph/Gmail ou envia e-mail; o smoke físico Windows continua externo.

## Limites desta fase

Não existem OCR, reconhecimento de DOCX/XLSX, conta compartilhada nem confirmação de entrega. A candidata 0.12.7 não inicia nova fase, não provisiona produção, não publica stable, não abre `Send` e não conecta OAuth por conta própria. Graph e Gmail permanecem restritos a registro/configuração externa e gates controlados; `fake.local` é o padrão. A cópia protegida cobre clientes e modelos da autoridade ativa, não PostgreSQL, PDFs, workspace de revisão, incidentes, tokens ou acervo. O catálogo local é de uma única instalação e não sincroniza automaticamente com o central. SQLite/pastas escolhidas dependem de FileVault/BitLocker e backup externo. Pacote/smoke macOS, CI da correção, piloto aceito, distribuição assinada/notarizada, smoke físico Windows, feed HTTPS, staging/produção provisionados, OAuth, restauração gerenciada e monitoramento continuam pendentes; os gates técnicos locais não promovem automaticamente a candidata a release distribuível.
