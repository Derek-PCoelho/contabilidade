# ADR-0011 — Liberação por cliente, fila atual e relatórios combináveis

- Status: aceito e implementado na correção candidata pós-Fase 12 0.12.6; gates técnicos locais aprovados, smoke visual final e CI pendentes
- Data: 2026-08-24
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O agrupamento mensal e conservador está correto, mas a apresentação anterior não permitia conferir todo o conjunto a partir de um documento. Um mesmo cliente com três competências aparecia como cinco documentos distribuídos em três conjuntos, e a ação por cliente permanecia presa ao recorte visual de um mês. Isso incentivava o operador a usar a aprovação geral sem entender quais mensagens independentes seriam criadas.

Em Envios, versões recompostas do mesmo grupo se acumulavam na lista acionável e uma seleção podia voltar para a versão substituída, fazendo a etapa visual regredir para **Preparar**. Relatórios tratavam cliente e período como alternativas, impossibilitando perguntas como “este cliente neste intervalo”. Histórico preservava a auditoria, porém apresentava pouco contexto operacional. Esses problemas são de coordenação e apresentação; não justificam afrouxar agrupamento, auditoria ou as fronteiras de produção.

## Decisão

1. **Preservar a unidade de agrupamento.** Um conjunto continua identificado por cliente, estabelecimento, `DocumentPeriod` efetivo e código/versão da política. Competências diferentes nunca são fundidas implicitamente; cada conjunto aprovado gera composição e mensagem independentes.
2. **Adicionar liberação explícita por cliente.** A operação recebe os IDs de todos os conjuntos aprováveis de um único cliente, inclusive entre competências. O serviço relê e revalida o workspace, exige que a seleção corresponda exatamente aos conjuntos aprováveis desse cliente e só então persiste todas as aprovações como uma unidade. Seleção incompleta, cliente misto ou qualquer conjunto solicitado que esteja ou fique bloqueado, com erro, duplicado ou invalidado concorrentemente falha antes da primeira gravação; outros conjuntos bloqueados do cliente não integram a seleção. A aprovação geral de vários clientes continua restrita a um único mês e ano.
3. **Separar visualização de mutação.** A interface permite listar todos os documentos do conjunto selecionado sem alterar a seleção individual. Organização manual inicia recolhida. **Separar** atua somente no documento selecionado; **unir** oferece apenas conjuntos com o mesmo cliente, estabelecimento, competência e código/versão da política. As duas ações exigem motivo, são auditadas, revalidam o agrupamento e invalidam snapshots de aprovação afetados.
4. **Projetar uma fila atual de mensagens.** A lista acionável escolhe, por `GroupId`, a versão corrente e não cancelada. Versões substituídas permanecem no workspace e na auditoria, mas não poluem a jornada comum. A referência curta da mensagem, cliente, competência e anexos compõem o rótulo e a busca. Depois de preparar, aprovar ou concluir, a seleção é restaurada pela identidade da nova mensagem/versão, preservando a etapa coerente.
5. **Compor filtros de relatório.** O filtro de cliente — todos ou um — é independente do filtro temporal — todos, mês, ano ou intervalo de competências mensais. A consulta aplica a interseção entre cliente e período igualmente a documentos e comunicações. XLSX e PDF registram a cobertura efetiva; o PDF relaciona tipos e nomes de todos os documentos do recorte, inclusive os que ainda não possuem mensagem, sem afirmar entrega quando existe apenas aceite técnico do provider.
6. **Projetar histórico operacional sem reescrever auditoria.** A UI deriva cliente, competência, tipos e quantidade dos estados persistidos e apresenta linguagem compreensível quando o vínculo necessário estiver disponível. Eventos antigos sem referência suficiente declaram o contexto indisponível, sem inventar associação. Ocultação por dia, hora cheia ou intervalo continua sendo uma preferência visual recuperável; eventos originais permanecem append-only.
7. **Manter competência global responsiva.** O cartão do cabeçalho reorganiza identificação/período e seletores de ano/mês em duas linhas quando necessário. Essa mudança não altera `DocumentPeriod`, persistência nem filtros.
8. **Não ampliar integração externa.** A correção não abre nova fase, OAuth, `Send`, provider real, publicação stable ou deploy. `fake.local`, piloto desativado e produção `Closed` continuam os padrões.

## Invariantes de segurança e consistência

- qualquer mudança de documento, cliente, competência, composição, split ou merge revoga as aprovações afetadas;
- bloqueados, erros e duplicados nunca entram em liberação rápida;
- a liberação por cliente não cria um “supergrupo” e não transforma competências diferentes numa única mensagem;
- a projeção de fila atual não apaga versões anteriores nem altera o resultado de tentativas;
- um filtro de relatório nunca amplia silenciosamente o outro eixo;
- limpar o Histórico nunca remove, reescreve ou compacta a auditoria funcional.
- o retorno de Envios para Documentos realinha o conjunto ao documento corrente; eventos agregados aparecem nas competências registradas; uma retirada preserva cliente, período e tipo; tentativa antiga permanece auditável, mas não integra o relatório corrente.

## Consequências

- O operador pode conferir um conjunto inteiro e, depois de validar o cliente, liberar todas as competências prontas desse cliente com uma ação única e atômica.
- O caminho geral continua mais restrito que o caminho deliberado por cliente, reduzindo mistura acidental entre clientes e meses.
- Mensagens repetidas do mesmo cliente permanecem distinguíveis por referência, competência e anexos, sem perder rastreabilidade de versões anteriores.
- Relatórios respondem a recortes combinados e explicam quais arquivos compõem os totais, inclusive quando o documento ainda não originou mensagem.
- A UI passa a depender de projeções derivadas, mas Domain/Application continuam sem dependência de Avalonia.
- A 0.12.6 permanece candidata até o smoke visual final do aplicativo instalado; a CI foi registrada como aprovada, mas os gates técnicos não autorizam distribuição ou operação externa.

## Evidências de verificação

- restore `--locked-mode`, formatação e build Release aprovados, com 0 warnings e 0 erros;
- 296 testes aprovados: 29 Domain, 80 Application, 73 Infrastructure, 33 Server e 81 UI;
- auditoria transitiva sem pacote vulnerável em nenhum projeto;
- XLSX inspecionado com `artifact_tool`, sem erros de fórmula e com contagem correta de conjuntos; PDF A4 de duas páginas renderizado e inspecionado;
- pacote macOS arm64 `validation` 0.12.6 com checksums íntegros, instalado em `/Applications/Folhas da Michelly.app`, binário instalado com hash idêntico ao pacote, versão `0.12.6.0` e assinatura ad-hoc;
- publish Windows `win-x64` gerado e confirmado como PE32+;
- CI 0.12.6 aprovada no commit `27121f7`: Ubuntu/PostgreSQL em 1m32s, macOS ARM64 em 2m13s e Windows x64 em 4m58s; smoke visual final macOS pendente porque o Mac está bloqueado;
- assinatura/notarização, pacote e smoke físico Windows, staging/produção, OAuth e envio real permanecem fora desta decisão e bloqueados.

## Relação com decisões anteriores

- Preserva o ADR-0006: competência global e agrupamento por período continuam fronteiras operacionais; a exceção por cliente aprova conjuntos separados, não os mistura.
- Preserva os ADRs 0008 e 0009: piloto e produção continuam fechados e não recebem autoridade da interface local.
- Preserva o ADR-0010: os perfis local e conectado continuam autoridades explícitas e sem merge automático.

## Alternativas consideradas

- **Unir automaticamente todas as competências do cliente:** rejeitada porque mistura períodos, altera anexos/mensagens e enfraquece a conferência contábil.
- **Manter a liberação por cliente limitada ao mês visível:** rejeitada porque obriga repetição operacional e não corresponde ao pedido humano explícito de tratar aquele cliente, embora continue apropriada para a aprovação geral.
- **Aprovar o subconjunto de IDs recebido e ignorar faltantes:** rejeitada porque cria sucesso parcial difícil de perceber e pode deixar competências prontas para trás.
- **Apagar mensagens substituídas:** rejeitada porque elimina rastreabilidade e pode ocultar tentativas já persistidas; apenas a projeção da fila comum é deduplicada.
- **Criar um único seletor com cliente ou período:** rejeitada porque os eixos respondem a perguntas diferentes e precisam ser combináveis.
- **Apagar eventos ao limpar o Histórico:** rejeitada porque viola a auditoria append-only; a limpeza permanece visual e reversível.
