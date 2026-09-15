# Plano de testes — Fase 12 com manutenção candidata pós-Fase 12 0.12.7

## Gates automatizados

```bash
dotnet restore --locked-mode
dotnet format FolhasDaMichelly.slnx --no-restore --verify-no-changes
dotnet build FolhasDaMichelly.slnx -c Release --no-restore
dotnet test FolhasDaMichelly.slnx -c Release --no-build --no-restore
dotnet list FolhasDaMichelly.slnx package --vulnerable --include-transitive
```

Publicações dry-run obrigatórias permanecem `osx-arm64` e `win-x64`.

## Cobertura acumulada até a candidata 0.12.7

| Suíte | Cobertura/evidência |
|---|---|
| Domain | CPF/CNPJ Mod11, raiz de estabelecimento, PF/PJ, e-mail/validade, bloqueio e concorrência cliente/template |
| Application/Infrastructure | cache do catálogo, dois clientes SQLite, fila/pull/checkpoint, conflito, idempotência, isolamento e audit append-only |
| PostgreSQL | aplicação limpa das migrations F2+F3 em PostgreSQL 18 real |
| Server | CRUD por tenant, documento mascarado, MFA, 409 obsoleto, inativação, auditoria redigida, template e import/export dry-run |
| UI | estado da Fase 8, importação/revisão, composição, conexão adaptada a Google/Microsoft, modos fake e regressões cadastrais |
| UX e hardening Fase 9 | PF/PJ, competência, ícones, backup protegido, incidentes, atalhos, progresso/cancelamento e retenção não destrutiva |
| Update e release Fase 10 | UI manual, troca de canal, feed fail-closed, SHA-256/adulteração, versão mínima, switches globais e scripts de release protegidos |
| Piloto supervisionado Fase 11 | modos permitidos, bloqueio triplo de Send, política staging, checklist/auditoria isolados, métricas e limites de parada |
| Produção gradual Fase 12 | prontidão fail-closed, papel/MFA, lote/cota central, idempotência, versão, painel read-only e configuração fechada |
| Correção pós-Fase 12 — 0.12.1 | catálogo/resolvedor SQLite padrão, modo HTTP explícito, cadastro e feedback, e-mail opcional de representante, importação multicompetência, filtro visível, layout e defaults técnicos fechados |
| Correção pós-Fase 12 — 0.12.2 | atualização/reativação e duplicidade cadastral recuperável; modelo com 20.000 caracteres e placeholders amigáveis; pendências acionáveis, competência corrigível, retirada lógica e limpeza de grupos vazios; permissões locais sem Send; estados responsivos de Clientes/Documentos/Envios/Configurações |
| Correção pós-Fase 12 — 0.12.3 | competência global sem duplicação e filtro mensal interanual; alteração cadastral + revalidação documental atômicas; e-mail legado recuperável; PF separada de CPF de sócio; modelos padrão; pasta mista com limites PDF explícitos; lotes de Documentos/Envios; relatório mensal profissional; histórico e layout responsivos |
| Correção focal pós-Fase 12 — 0.12.4 | lista/cadastro recolhíveis com foco automático; fluxo documental único Adicionar → Conferir → Liberar; uma rolagem externa; identificação e agrupamento explicados; correção/retirada acionável e operações excepcionais recolhidas |
| Correção pós-Fase 12 — 0.12.5 | conjunto para mensagem explicado; liberação selecionada/cliente/todos prontos; painéis opcionais recolhidos; Envios vertical e modo imutável; limpeza visual recuperável; relatórios por mês/cliente/todos em XLSX/PDF/CSV |
| Correção candidata pós-Fase 12 — 0.12.6 | visualização integral do conjunto; liberação atômica por cliente entre competências; organização manual compatível; fila atual deduplicada; relatório por cliente e período combinados; histórico contextual e cartão de competência responsivo |
| Manutenção candidata pós-Fase 12 — 0.12.7 (gates locais e CI aprovados) | CPF de representante acionável; escritório padrão; cartão Competência; inventário de relatório com snapshots; filtros/fidelidade/limpeza exata do Histórico; layout de Relatórios e Histórico |
| Reconhecimento | sete PDFs dourados, texto/página/posição, folha de 3 páginas deduplicada, valores contábeis normalizados |
| Segurança PDF | assinatura MIME, corrompido, limite de páginas, cancelamento, SHA/cache e `NeedsOcr` |
| Resolução | empregador vence sindicato/empregado, isolamento tenant, raiz ambígua bloqueia, fuzzy não autoriza |
| Minimização | servidor não recebe CPF do trabalhador/sócio, CNPJ sindical nem valores financeiros |
| Período | competência mensal/anual, apuração, intervalo, evento e vencimento preservados separadamente |
| Validação | integridade/hash, campos obrigatórios, cliente, valor zero/negativo, vencimento e raízes CNPJ distintas |
| Duplicidade | SHA-256 e chave semântica bloqueiam, ficam sem grupo e não entram em aprovação em lote |
| Agrupamento | política mensal/evento, split, merge, rejeição entre clientes/períodos e auditoria |
| Aprovação | blockers/errors não aprovam; arquivo, override ou composição alterada revogam snapshot |
| Recuperação | revisão, grupo aprovado e auditoria recuperam após novo DbContext; escopos organizacionais isolados |
| Composição | rota determinística, placeholders, fallback bloqueante, texto/HTML seguro, snapshot e fingerprint |
| Modos | individual e lote sequencial; Test, Draft e Send simulados; Test troca destino por `example.invalid` e preserva o original |
| Políticas | permissões, confirmação, kill switch e versão mínima; Test permanece disponível quando Send bloqueia |
| Idempotência | tentativa antes da chamada, timeout/ambíguo sem retry cego, reconciliação e terminal duplicado bloqueado |
| Fake provider | recibo local atômico, IDs fake, cenários success/transient/permanent/timeout/ambiguous e nenhum `HttpClient` |
| Relatórios | recortes mês/cliente/todos, resumo executivo, XLSX/PDF/CSV, documentos sem mensagem, pendências documentais, filtros/freeze panes, datas tipadas, colunas técnicas recolhidas e proteção contra formula injection |
| Histórico visual | ocultação recuperável por tudo/competência/dia/hora/intervalo sem remover ou reescrever a auditoria append-only |
| Cofre nativo | round-trip/removal real no Keychain do Mac e DPAPI CurrentUser no runner Windows; nenhum segredo em arquivo legível |
| OAuth Microsoft | configuração fail-closed, sessão separada, desconexão e auditoria; revogação vira `AUTH_REVOKED` sem HTTP |
| Destino Graph | rota original preservada, destino efetivo único/controlado, Cc vazio, prefixo e confirmação Graph distinta |
| Graph draft/send | draft-first, propriedade idempotente, `Prefer: ImmutableId`, fileAttachment e HTTP 202 mapeado somente para aceite |
| Anexo grande | upload session HTTPS, chunk múltiplo de 320 KiB, `Content-Range` e URL pré-autorizada sem bearer token |
| Retry/recovery | `Retry-After` em GET idempotente, Send timeout chamado uma vez, reconciliação sent/draft e retomada com nova confirmação |
| Preflight central | `Test`/`Send` Graph/Gmail exigem `email.send` + MFA + sessão ativa; switches/versões são independentes e a auditoria omite fingerprint/destino |
| OAuth Google | loopback IPv4, PKCE S256, ausência de client secret/OOB, `gmail.compose` único, cofre separado, refresh e revogação |
| Destino Gmail | rota original preservada, destino efetivo único/controlado, Cc vazio, prefixo F8 e confirmação Gmail distinta |
| Gmail draft/send | MIME UTF-8, texto/HTML, PDF, base64url, IDs retornados, draft-first e Send por ID |
| Gmail retry/recovery | GET idempotente limitado, Send timeout chamado uma vez, draft confirmado como não enviado e ausência mantida ambígua |

O teste `PostgreSqlMigrationsApplyWhenIntegrationConnectionIsConfigured` executa o gate real quando `FOLHAS_TEST_POSTGRES` está definido. Sem essa variável, a suíte cross-platform continua válida e o job Linux dedicado garante o provider de produção.

## Suite Microsoft Graph live opt-in

`Phase7MicrosoftGraphLiveTests` é marcado com trait `MicrosoftGraphLive`. Sem `FOLHAS_GRAPH_LIVE_TEST=true`, retorna antes de criar sessão/chamada. No gate manual:

- requer public client, conta dedicada já conectada ao cofre e destinatário controlado;
- cria draft e anexo sintéticos e remove o draft ao final;
- só envia se `FOLHAS_GRAPH_LIVE_SEND=true` também estiver explícito;
- nunca usa cliente, documento ou destinatário real de produção.

## Suite Gmail live opt-in

`Phase8GmailLiveTests` é marcado com trait `GmailLive`. Sem `FOLHAS_GMAIL_LIVE_TEST=true`, retorna antes de criar sessão/chamada. No gate manual:

- requer client OAuth Desktop, conta Google dedicada já conectada ao cofre e destinatário controlado;
- cria draft, MIME e anexo sintéticos e remove o draft ao final;
- só envia se `FOLHAS_GMAIL_LIVE_SEND=true` também estiver explícito;
- nunca usa cliente, documento ou destinatário real de produção.

## Matriz CI

A execução remota da 0.12.7 foi aprovada na CI `32873335418`; o empacotamento `validation` macOS/Windows também passou na execução `32873883491`. A matriz obrigatória permanece:

- macOS 26/`osx-arm64`: restore bloqueado, format, build, testes offline/default e publish de validação;
- Windows 2025/`win-x64`: mesmos gates e publish;
- Ubuntu + PostgreSQL 18: migrations Npgsql e testes de sync;
- Ubuntu semanal: auditoria transitiva de vulnerabilidades.

## Gates específicos da Fase 9

- a 11ª requisição de autenticação da mesma partição/minuto retorna 429, `Retry-After` e correlação genérica;
- CSP, frame denial, no-sniff e correlação normalizada são validados sem eco de query/dado pessoal;
- backup faz round-trip AES-GCM, não contém texto claro e trata senha errada/adulteração da mesma forma;
- o redator remove e-mail, CPF/CNPJ, bearer/JWT e segredos antes da persistência;
- incidentes exigem tentativa, bloqueiam duplicidade ativa, validam transições/justificativa/versão e recuperam histórico append-only isolado após restart SQLite;
- atalhos `Ctrl+1` a `Ctrl+7`, nomes de automação, heading, status/progresso vivo, cancelamento cooperativo e limite de 500 eventos integram o gate de acessibilidade/desempenho;
- análise de retenção é somente leitura e nenhum comando automático de exclusão existe.

## Gates específicos da Fase 10

- Velopack inicia antes da UI e o auto-apply está explicitamente desligado;
- a UI não chama Apply antes da verificação e do download; mudar Stable/Beta invalida o estado pendente;
- feed ausente, HTTP, com credencial ou query falha fechado; feed local exige opt-in;
- SHA-256 correto é aceito e conteúdo sintético adulterado é rejeitado;
- Server exige autenticação para a política de release, rejeita versão malformada e aplica mínimo global;
- kill switch global bloqueia Graph e Gmail mesmo quando o switch do provider está ligado;
- scripts separam RID/canal, recusam stable não assinada, marcam validation como não distribuível e reconferem hashes;
- workflow de release é somente manual, usa input por variável de ambiente e reserva assinatura a environments protegidos;
- pacote real `osx-arm64-beta validation` abre no Apple Silicon, preserva versão/ícone e fecha sem acessar feed/OAuth/e-mail;
- gate assinado exige Authenticode válido no Windows e Developer ID + notarização + Gatekeeper no macOS.

## Gates específicos da Fase 11

- UI configurada para piloto contém apenas Teste e Rascunho; Send não é uma opção;
- workflow recusa Send antes de criar item/lote e repete a trava antes da execução;
- preflight central distingue Test de Send, permite Test controlado e retorna `PILOT_SEND_DISABLED` para Send;
- endpoint de política exige autenticação, reporta staging/limite/versão e falha fechado com configuração inválida;
- os seis itens do checklist recuperam após restart, preservam auditoria append-only e não cruzam organizações;
- prontidão falha com tentativa Send, falha/ambiguidade, incidente alto/crítico, mais de cinco clientes ou checklist incompleto;
- template/script de staging exigem HTTPS remoto, health pronto e todos os switches seguros;
- QA visual percorre Envios e Configurações sem conectar OAuth nem executar operação de e-mail;
- publish dry-run `osx-arm64`/`win-x64`, varredura de vulnerabilidades e busca por segredo/dado real permanecem obrigatórios.

## Gates específicos da Fase 12

- defaults e configuração Production mantêm `Closed`, provider/F10/F12 falsos e fiscalização obrigatória;
- avaliador só fica pronto com piloto aceito/desativado, stable, backup/restore, monitoramento, incidentes, suporte, limites e versão válidos;
- UI bloqueada remove `Send` e mostra estágio/gates sem comando para abrir produção;
- workflow externo recusa `Send` antes da composição quando a prontidão está falsa;
- política e preflight exigem `email.send`, MFA e sessão; papel `Operator` não autoriza produção;
- lote acima do limite e cota diária por organização retornam códigos próprios antes do provider;
- repetir operação/fingerprint não consome nova cota; reutilizar a operação com fingerprint divergente falha por conflito;
- tabela PostgreSQL de autorizações é append-only, tenant-scoped e aplicada por migration F12;
- template e script de produção exigem HTTPS, versão 0.12.0, papel da sessão, prontidão e bloqueadores vazios;
- nenhum teste default conecta OAuth, executa provider externo ou altera ambiente remoto.

## Gates da correção pós-Fase 12

- sem `Phase2:ApiBaseAddress`, a DI registra `SqliteLocalClientCatalogService`, `SqliteLocalClientResolver` e piloto desativado;
- com URI explícita, a DI preserva `HttpClientCatalogService`/`HttpClientResolver` e não registra o catálogo local como fallback;
- o catálogo local salva, busca por nome/CPF/CNPJ, reabre, inativa, calcula prontidão e audita sem expor o documento fiscal completo;
- regras de domínio, unicidade e concorrência otimista valem no SQLite; cliente/template fazem export, prévia `DryRun` e restauração após novo contexto;
- o resolvedor local cobre CNPJ exato e raiz CNPJ + nome, mantém evidência e bloqueia ambiguidades/inativos pelas regras existentes;
- revisão documental pode reexecutar a resolução depois que um cliente é cadastrado, sem depender do Server no perfil local;
- os namespaces `local-*` impedem que o catálogo autônomo leia ou altere o cache do perfil conectado;
- editar, inativar ou trocar a correspondência cadastral revalida documentos, bloqueia mudança silenciosa de cliente e invalida aprovações anteriores; confirmação manual só permanece enquanto a alternativa continuar válida;
- cliente vazio não persiste, sucesso aparece inline e atualiza a lista; CPF/CNPJ podem ser digitados com ou sem pontuação;
- e-mail opcional do sócio/representante é validado, persistido nos dois perfis e pode gerar o contato de entrega sem duplicação;
- importação reconhece a competência antes do acervo, deduplica por SHA-256, organiza cada arquivo em `AAAA/MM` e deixa todas as competências visíveis em lote misto;
- ações em lote exigem um único mês/ano e nunca aprovam ou executam itens ocultos por outro recorte de competência;
- UI mantém `fake.local`, piloto desativado e produção fechada por padrão; ações Google/Microsoft não conectam sem provider configurado;
- retenção oferece também 2, 3 e 6 meses, apenas para análise; nenhuma exclusão automática foi adicionada;
- nenhum teste default abre OAuth, chama Graph/Gmail, envia e-mail, publica release ou altera ambiente remoto.

### Casos acrescentados na correção 0.12.2

- inativar e reativar persistem imediatamente; a lista pode exibir inativos e mantém o registro selecionado após a mudança;
- salvar uma edição atualiza o cadastro existente; repetir CPF/CNPJ de registro inativo recupera esse registro sem apagar nem duplicar;
- corpo de modelo com 161 e até 20.000 caracteres é aceito, 20.001 é recusado e placeholder desconhecido bloqueia salvamento/composição;
- catálogo de placeholders expõe somente as dez chaves suportadas, com rótulo amigável, validação e substituição determinística;
- `PeriodOverride` sobrevive ao round-trip JSON, payload legado sem a propriedade continua legível e restaurar o período extraído remove o override;
- corrigir competência, restaurá-la ou retirar documento revoga aprovação afetada, reagrupa e preserva auditoria;
- retirar da revisão não apaga o PDF físico e grupos sem documentos são eliminados também ao carregar um workspace legado;
- `LocalDesktopOperationContextAccessor` entrega escopo local estável e exatamente `documents.process`, `batch.approve`, `email.draft` e `audit.export`, sem `email.send`;
- o perfil conectado continua usando `JwtDocumentReviewContextAccessor`; sem token válido, usa escopo isolado `unauthenticated-connected` e conjunto vazio de permissões;
- estados de comandos da UI impedem aprovar/executar sem seleção válida e preservam mensagens de sucesso/erro cadastral.
- falhas em pesquisa, leitura ou atualização durante recuperação de CPF/CNPJ preservam o rascunho e retornam feedback amigável;
- inativar um modelo enquanto ele está aberto no editor sincroniza versão/estado e um salvamento posterior não o reativa;
- item de envio `Failed` só pode ser repetido quando approval/fingerprint continuam válidos e a última tentativa correspondente terminou em `FailedTransient`; falha permanente permanece bloqueada.

### Casos acrescentados na correção 0.12.3

- reconstruir a lista de anos repetidamente preserva uma única opção para cada ano; trocar de área e reiniciar mantém a competência global selecionada;
- busca cadastral por Enter, abertura por clique e alternância opcional de máscara fiscal preservam a seleção e não alteram CPF/CNPJ armazenado;
- status de cliente é persistente e idempotente; salvar/inativar/reativar e revalidar aprovações documentais confirmam juntos, e falha depois de `SaveChanges` reverte cadastro, auditoria e revisão;
- falha de atualização visual posterior ao commit reflete o estado persistido, encerra o indicador de progresso e orienta atualizar a tela; e-mail legado inválido já inativo não bloqueia prontidão nem edição não relacionada;
- cadastro legado com e-mail incompleto continua pesquisável/abrível, não é alterado durante a leitura, informa `RECIPIENT_EMAIL_INVALID` internamente e mostra ao usuário a correção necessária; salvamento sem corrigir continua recusado;
- e-mail novo sem domínio completo é recusado; CPF de sócio não resolve documento para a empresa nem impede cadastrar a pessoa física do mesmo CPF;
- exclusão de cliente/modelo é lógica, exige estado seguro, respeita referências operacionais e preserva auditoria;
- predefinições de empresa/PF preenchem assunto e corpo, e o seletor insere campos suportados tanto no assunto quanto no corpo;
- retirada do documento permite repetir teste sem apagar o PDF; pasta mista importa PDFs e relata DOCX/XLSX/outros recusados e preservados; assinatura MIME incompatível não segue para o parser;
- aprovação individual e mensal respeitam a competência global; ações de lote permanecem disponíveis quando outro item do lote é elegível; resumo aprovado nunca exibe identificador técnico do operador nem a palavra `snapshot`;
- “agosto de todos os anos” inclui somente eventos de agosto no Histórico;
- Teste seguro informa explicitamente que nenhum e-mail sai da máquina e não equivale a confirmação de entrega;
- exportação exige um único mês, produz cinco visões profissionais, não contém erros de fórmula e foi renderizada/inspecionada visualmente; Gmail/Graph preparados sem tentativa mostram o serviço correto sem alegar envio;
- smoke do bundle instalado cobriu lista de anos única, busca Enter, abertura de legado, inativação/reativação, sete áreas e janela mínima com rolagem acessível.

### Casos acrescentados na correção 0.12.4

- recolher a lista mantém o cadastro aberto; recolher o cadastro mantém a lista aberta; tentar recolher a última camada útil reabre a outra automaticamente;
- **Novo cliente** abre o editor e recolhe a lista, preservando um caminho visível para restaurá-la;
- documento resolvido mostra cliente, CPF/CNPJ mascarado, método de resolução e organização por cliente/estabelecimento/competência sem expor identificador interno;
- documento não resolvido informa que nenhuma associação automática foi feita, abre a correção e nunca atribui cliente ou grupo por suposição;
- smoke do bundle instalado em janela mínima confirmou uma única rolagem externa em Documentos, com correção, retirada e liberação acessíveis, e o foco integral do formulário de Clientes.

### Casos acrescentados na correção 0.12.5

- a explicação de **conjunto para mensagem** descreve a unidade que seguirá junta sem expor chave, política ou identificador técnico;
- liberar o conjunto selecionado aprova somente seus documentos; liberar o cliente inclui todos os conjuntos prontos daquele cliente; liberar tudo inclui somente os conjuntos prontos do recorte;
- bloqueios, erros e duplicidades nunca entram nas liberações rápidas, e itens de outros clientes/recortes permanecem inalterados;
- correção/retirada, organização manual e evidências de identificação começam recolhidas inclusive quando um documento bloqueado é selecionado;
- Envios mantém os quatro passos na ordem vertical, não permite trocar o modo depois da preparação e preserva destinatário, anexos, aprovação e fingerprint da composição;
- envio individual e conclusão conjunta exibem e usam confirmações independentes; a sequência soma somente mensagens aprovadas e seus anexos;
- todos os tipos e estados apresentados em Envios usam português; `fake.local` informa que a operação é simulação, não envia e não comprova chegada;
- o resultado histórico usa o provedor gravado na tentativa/mensagem, mesmo quando a conexão atualmente selecionada é outra; rascunho `fake.local` conta como simulação concluída, não como rascunho em conta real;
- limpar a visualização do Histórico por tudo, competência, dia, hora ou intervalo oculta somente os eventos abrangidos; restaurar torna-os visíveis novamente e um novo contexto preserva a auditoria append-only original;
- relatórios por mês, cliente e todos os períodos incluem documentos ainda sem mensagem, pendências documentais e estados de comunicação sem misturar recortes;
- o seletor de cliente do relatório é carregado ao entrar em Relatórios, inclui cadastros inativos e clientes presentes somente no histórico e não depende de abrir Clientes antes;
- cada exportação produz XLSX, PDF e CSV coerentes; planilhas e todas as páginas do PDF devem ser renderizadas e inspecionadas visualmente, sem fórmulas inválidas, cortes ou sobreposição;
- os relatórios distinguem sem tentativa, simulação local, falha, resultado incerto e aceitação técnica; nenhum deles transforma `AcceptedByProvider` em entrega comprovada.

### Casos acrescentados na correção 0.12.6

- selecionar um documento permite abrir a lista completa do seu conjunto, com todos os arquivos, tipos e competência, sem alterar o documento individual selecionado;
- o agrupador continua separando cliente, estabelecimento, competência e código/versão da política: documentos de agosto, setembro e dezembro do mesmo cliente formam conjuntos distintos;
- **Liberar este cliente** aceita IDs explícitos de todos os conjuntos prontos daquele cliente, inclusive em competências diferentes, valida todos antes da primeira alteração e mantém uma mensagem independente para cada conjunto;
- seleção incompleta dos conjuntos prontos do cliente, mistura de clientes ou qualquer conjunto solicitado que esteja ou fique bloqueado, com erro, duplicado ou invalidado concorrentemente cancela toda a liberação por cliente, sem aprovação parcial; conjuntos bloqueados que não foram solicitados permanecem fora da ação, e **Liberar tudo pronto** continua exigindo um único mês e ano;
- a organização manual começa recolhida; separar atua somente no documento selecionado e unir oferece apenas outro conjunto com o mesmo cliente, estabelecimento, competência e código/versão da política. As duas ações exigem motivo suficiente, auditam a alteração e invalidam snapshots afetados;
- a fila comum de Envios preserva somente a versão corrente de cada `GroupId`; composição substituída/cancelada não se acumula, mas continua disponível no histórico/auditoria;
- cada mensagem corrente expõe referência curta, cliente, competência e quantidade/lista de anexos; busca encontra esses campos e filtros distinguem pendentes, concluídas e todas;
- preparar novamente, aprovar ou concluir mantém selecionada a nova versão da mensagem e avança ou preserva a etapa coerente, sem regressar visualmente a **Preparar** por seleção de item substituído;
- voltar de Envios para Documentos realinha o conjunto selecionado ao documento corrente, sem manter um conjunto visualmente obsoleto;
- o filtro de relatório combina cliente — todos ou um selecionado — com período — todos, mês, ano ou intervalo mensal inclusivo, por exemplo `08/2026 a 12/2026` — aplicando a interseção nos documentos e nas comunicações;
- XLSX e PDF declaram cliente/período efetivamente aplicados; o PDF lista tipos e nomes de todos os documentos do recorte, inclusive os que ainda não possuem mensagem, e continua distinguindo ausência de mensagem, simulação, falha, incerteza, aceite técnico e entrega não comprovada;
- Histórico apresenta frases operacionais com cliente, competência, tipos e quantidades quando o vínculo persistido permitir; eventos antigos sem contexto suficiente informam essa indisponibilidade, sem recorrer ao nome técnico aleatório do arquivo. Dia, uma hora cheia e intervalo personalizado delimitam exatamente a limpeza visual recuperável;
- um evento agregado é projetado em cada competência registrada; a retirada preserva cliente, período e tipo na descrição histórica; uma tentativa antiga/substituída não integra o relatório corrente;
- o cartão de Competência mantém rótulo e período alinhados acima dos seletores de ano/mês em largura reduzida, sem corte ou sobreposição;
- restore bloqueado, formatação, build Release, **296 testes aprovados** — 29 Domain, 80 Application, 73 Infrastructure, 33 Server e 81 UI —, auditoria de vulnerabilidades, QA XLSX/PDF, empacotamento macOS, publish Windows e CI multiplataforma integram o fechamento técnico. O smoke visual macOS ainda precisa ser registrado antes de declarar a candidata integralmente concluída. Testes default não abrem OAuth, não chamam Graph/Gmail, não enviam e-mail e não fazem deploy.

### Casos acrescentados na manutenção 0.12.7

- texto de e-mail digitado no campo CPF do representante produz orientação específica, mantém o valor para correção e não adiciona o representante; CPF vazio continua permitido, e CPF pontuado ou sem pontuação segue a mesma validação matemática;
- erros do nome ou e-mail do representante não são ocultados apenas por editar o CPF, e o rascunho de representante não atravessa a abertura de outro cliente;
- toda composição que publique `{{escritorio.nome}}` usa **AL Contadores Associados**;
- o cartão de Competência preserva borda, respiros e alinhamento do período/seletores na largura padrão e na janela mínima suportada;
- o inventário de Relatórios deduplica por `DocumentId`, prefere o documento corrente e inclui anexos presentes somente no snapshot imutável da mensagem;
- o cenário sintético BOREAL com três anexos, um deles ausente da revisão corrente, produz exatamente três documentos no XLSX, CSV e PDF. A base local observada contém apenas dois PDFs BOREAL persistidos e não serve como evidência de um terceiro documento real;
- XLSX mantém cabeçalhos/colunas legíveis, congelamento e colunas técnicas recolhidas; PDF A4 deve ser renderizado integralmente, sem corte, sobreposição ou perda de documento;
- Histórico combina competência, dia, faixa horária, intervalo, mês, ano, cliente, documento e texto; as seções de documentos/comunicações podem ficar recolhidas sem alterar o filtro;
- documento retirado continua pesquisável pelo `DocumentId` registrado no evento e não passa a corresponder a outro documento que permaneça no grupo; correção/restauração de competência considera o período anterior e o novo;
- a auditoria cadastral troca de cliente no próprio Histórico, sem depender do cliente aberto no editor;
- **Tudo que aparece agora** persiste somente os IDs atualmente visíveis, é restaurável e não oculta eventos fora do recorte; falha ao carregar auditoria não pode ser substituída por mensagem falsa de sucesso;
- restore bloqueado, formatação e build Release passaram; **327 testes** foram aprovados — 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI — e a auditoria transitiva encontrou zero pacote vulnerável. Os testes default permaneceram offline e fail-closed;
- o QA confirmou XLSX com 5 abas/tabelas, 3 documentos sintéticos e nenhum erro de fórmula; o PDF foi renderizado em 2 páginas A4 sem corte ou sobreposição;
- o aplicativo 0.12.7 instalado percorreu Início, Relatórios e Histórico em 1170×768 e 960×640. O pacote macOS teve `SHA256SUMS.txt` aprovado; o cross-publish Windows produziu 341 arquivos e PE32+ GUI x86-64, sem smoke físico.

## Gates manuais ainda necessários

- login interativo no navegador do sistema com callback PKCE em macOS e Windows;
- round-trip DPAPI e UI OAuth em Windows real;
- consentimento/revogação Microsoft e suite `MicrosoftGraphLive` em conta/destino dedicados;
- consentimento/revogação Google, suite `GmailLive` e comportamento do escopo restrito em conta/destino dedicados;
- configuração/verificação da tela de consentimento Google antes de qualquer distribuição externa;
- smoke real do Desktop em Windows 11 e decisão sobre Windows 10;
- provisionamento/rotação real dos certificados OIDC, TLS e restore de PostgreSQL/acervo antes de dados reais.
- assinatura/update/reparação com certificado Windows em Windows 11 real;
- assinatura Developer ID, notarização e update/reparação com Xcode completo no macOS;
- publicação e leitura dos quatro feeds em endpoint HTTPS somente leitura.

Esses gates físicos são parte do aceite operacional da Fase 11 e pré-requisito da abertura F12; não cobrem OCR, conta compartilhada ou confirmação de entrega. Send/produção permanecem proibidos enquanto o runbook F12 não for integralmente aprovado.

A correção 0.12.1 encerrou com 175 testes aprovados: 18 Domain, 44 Application, 53 Infrastructure, 33 Server e 27 UI. A correção 0.12.2 encerrou com 197 testes: 21 Domain, 54 Application, 54 Infrastructure, 33 Server e 35 UI. A correção 0.12.3 encerrou com 244 testes: 29 Domain, 56 Application, 70 Infrastructure, 33 Server e 56 UI. A correção focal 0.12.4 encerrou os gates locais com 248 testes aprovados: 29 Domain, 56 Application, 70 Infrastructure, 33 Server e 60 UI. A correção 0.12.5 encerrou com **267 testes aprovados**: 29 Domain, 65 Application, 72 Infrastructure, 33 Server e 68 UI. A candidata 0.12.6 passou restore `--locked-mode`, formatação e build Release com 0 warnings/0 erros e **296 testes aprovados**: 29 Domain, 80 Application, 73 Infrastructure, 33 Server e 81 UI. A 0.12.7 passou os mesmos gates locais com **327 testes aprovados**: 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI; zero pacote vulnerável. XLSX de 5 abas/tabelas com 3 documentos sintéticos e PDF A4 de 2 páginas passaram pelo QA visual. O pacote macOS arm64 `validation` foi instalado como 0.12.7.0 com checksums íntegros e backup recuperável da 0.12.6.0; o cross-publish Windows gerou 341 arquivos e executável PE32+ GUI x86-64. A CI tripla `32873335418` e os pacotes internos `validation` da execução `32873883491` foram aprovados. Pacote assinado/notarizado, smoke físico Windows, staging/produção HTTPS, OAuth, envio real, restore gerenciado e rollback assinado permanecem pendentes ou condicionados à infraestrutura/autorização externa.
