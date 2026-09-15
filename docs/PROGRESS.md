# Progresso — Folhas da Michelly

- Atualizado em: 2026-08-25
- Última implementação: manutenção candidata pós-Fase 12 0.12.7 em validação cadastral, Competência, Relatórios e Histórico; gates locais e CI tripla, QA, instalação macOS e pacotes `validation` macOS/Windows aprovados (sem nova fase)
- Próxima fase autorizada: nenhuma
- Repositório remoto: `https://github.com/danziatus/folhas-da-michelly`
- Envio real: adaptadores Graph/Gmail implementados, desabilitados por padrão e nunca executados; somente contas/destinos controlados nos gates live
- Parser PDF: implementado para texto nativo; OCR: inexistente
- Dados operacionais reais no repositório: nenhum; três assets de identidade autorizados e registrados em `docs/BRAND_ASSETS.md`

## Estado das fases

| Fase | Estado | Evidência |
|---|---|---|
| 0 — Descoberta e planejamento | Concluída | specs, plano e ADRs |
| 1 — Scaffold e CI | Concluída | gates locais e CI Windows/macOS aprovados |
| 2 — Backend, identidade e sync | Implementação concluída; aceite condicionado | PostgreSQL, API/RBAC/OIDC/sync; gates interativos de cofre/PKCE pendentes |
| 3 — Cadastro central | Concluída | PF/PJ, CRUD, import/export, concorrência, bloqueio, auditoria, cache e CI tripla verde |
| 4 — Ingestão e reconhecimento | Concluída | PdfPig isolado, sete perfis, evidências, resolução tenant-safe, cache e CI tripla verde |
| 5 — Validação e agrupamento | Concluída | períodos tipados, 8 regras, duplicidade, grupos, split/merge, override, snapshots, SQLite e CI tripla verde |
| 6 — Workflow UI + FakeEmailProvider | Concluída | individual/lote, Test/Draft/Send locais, tentativa antes da chamada, reconciliação, XLSX/CSV e CI tripla verde |
| 7 — Microsoft Graph | Implementação offline concluída; aceite live condicionado | OAuth delegado/MSAL, cofre nativo, destino controlado, drafts, anexos, send, retry/reconciliação; conta Microsoft 365 dedicada ausente |
| 8 — Gmail API | Implementação offline concluída; aceite live condicionado | OAuth Desktop/PKCE, cofre nativo, MIME, destino controlado, drafts, anexos, send e reconciliação; projeto/conta Google dedicados ausentes |
| 9 — Hardening e incidentes | Implementação concluída; aceite operacional condicionado | threat model, rate limits, redação, incidentes, backup protegido/restore, retenção não destrutiva e acessibilidade; restore de infraestrutura real ainda é gate externo |
| 10 — Update e packaging | Implementação offline concluída; aceite assinado condicionado | Velopack, quatro canais, CI tripla verde e pacotes de validação macOS/Windows aprovados; certificados/feed/smokes assinados ausentes |
| 11 — Piloto supervisionado | Implementação local concluída; aceite operacional bloqueado | política staging, Test/Draft sem Send, checklist, métricas e rollback; staging/certificados/estações físicas ausentes |
| 12 — Produção gradual | Implementação local concluída; abertura operacional bloqueada | papéis/MFA, lote/cota, idempotência, versão, kill switches, painel e runbook prontos; piloto/stable/restore/monitoramento/smokes externos ausentes |

## Manutenção de precisão cadastral, relatórios e histórico 0.12.7 após a Fase 12

- o CPF opcional do sócio/representante ganhou validação acionável no próprio formulário: texto que se pareça com e-mail, formato incompleto e dígito verificador inválido são distinguidos para que o operador saiba exatamente o que corrigir, sem transformar o CPF opcional em obrigatório;
- `{{escritorio.nome}}` passa a usar **AL Contadores Associados** como nome padrão do escritório nas composições; a alteração não habilita provider, OAuth ou envio;
- o cartão global de **Competência** foi reajustado para manter período, ano e mês dentro do retângulo, com proporções e alinhamento coerentes também em larguras menores;
- Relatórios preserva os filtros combinados já existentes, mas reorganiza a tela, os resumos e as ações. XLSX e PDF passam a usar um inventário documental unificado: o estado corrente de revisão é preferido quando existe e o snapshot imutável dos anexos da mensagem preserva o documento que já não esteja presente na revisão corrente;
- a base local observada da **BOREAL TECNOLOGIA SINTETICA LTDA** contém objetivamente **dois PDFs persistidos**, `T06_BOREAL_FOLHA_2026-08.pdf` e `T07_BOREAL_RESCISAO_2026-08.pdf`; a documentação não inventa um terceiro item operacional. Uma regressão inteiramente sintética cobre três anexos, dos quais um existe somente no snapshot da mensagem, e exige os três no XLSX, CSV e PDF;
- Histórico ganha filtros combináveis por dia, faixa de horas, intervalo, mês, ano, cliente, documento e competência global. Documentos conferidos/aprovados e comunicações podem iniciar recolhidos; a auditoria cadastral é consultada pelo cliente escolhido no próprio Histórico, sem depender do editor de Clientes;
- a projeção histórica conserva a identidade do documento registrada no evento: retirada ou mudança posterior de um grupo não pode reatribuir o evento a outro documento ainda existente. Correções/restaurações de competência mantêm pesquisáveis os períodos anterior e novo;
- **Limpar tudo que aparece agora** oculta exatamente os eventos visíveis no recorte corrente, por IDs, sem ampliar a limpeza a eventos fora do filtro. A operação continua exclusivamente visual, recuperável e sem apagar ou reescrever a auditoria append-only;
- a versão foi elevada para **0.12.7**. Restore bloqueado, formatação, build Release com 0 warnings/0 erros e **327 testes** passaram — 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI —; a auditoria transitiva encontrou zero pacote vulnerável;
- o QA de relatórios confirmou XLSX com cinco abas/tabelas e três documentos sintéticos sem erro de fórmula, além de PDF com duas páginas A4 sem corte ou sobreposição. O inventário testado contém três anexos, dois documentos correntes e um item existente somente no snapshot;
- o aplicativo instalado foi percorrido em **Início**, **Relatórios** e **Histórico** nas dimensões 1170×768 e 960×640, com o cartão de Competência, filtros, seções recolhíveis e ações principais acessíveis e alinhados;
- o pacote macOS arm64 `beta validation` foi gerado em `artifacts/phase10/osx-arm64-beta/0.12.7`; `SHA256SUMS.txt` foi aprovado e a versão `0.12.7.0` foi instalada em `/Applications/Folhas da Michelly.app`. A instalação anterior `0.12.6.0` permanece recuperável em `artifacts/install-backups/Folhas da Michelly 0.12.6 pre-0.12.7.app`;
- o bundle macOS usa assinatura ad-hoc: a verificação estrita de assinatura falha como esperado para `validation-only`, portanto o artefato não é distribuível nem substitui Developer ID/notarização;
- o cross-publish Windows `win-x64` produziu 341 arquivos; o executável é PE32+ GUI x86-64 e tem SHA-256 `6b4872d415cdf00dd56190908df9676a527536d3140ee29cf0bc33d30bde1c0e`. Não houve instalação nem smoke em Windows físico;
- não foi necessário novo ADR: a manutenção permanece dentro das fronteiras e invariantes do ADR-0011;
- o commit `9119cb2` foi enviado à `main`; a CI `32873335418` aprovou Ubuntu/PostgreSQL 18, macOS arm64 e Windows x64, e o workflow `32873883491` gerou os pacotes internos `validation` nas duas plataformas. Este incremento não inicia nova fase. OAuth, conta externa, Draft/Send real, pacote assinado/notarizado e smoke físico Windows permanecem checkpoints separados e fail-closed.

## Correção de precisão operacional e UX 0.12.6 após a Fase 12

- o agrupamento contábil permanece conservador: um conjunto reúne somente documentos com o mesmo cliente, estabelecimento, competência e código/versão da política; competências distintas continuam em conjuntos e mensagens independentes;
- Documentos passa a permitir a inspeção do conjunto completo, sem perder a seleção individual. A ação explícita **Liberar este cliente** aprova, em uma única operação atômica, todos os conjuntos prontos daquele cliente, mesmo quando pertencem a competências diferentes; seleção incompleta, cliente misto ou qualquer conjunto solicitado que esteja ou fique bloqueado impede toda a gravação, enquanto outros conjuntos bloqueados do cliente permanecem fora da liberação e a aprovação geral continua limitada a um único mês;
- a organização manual inicia recolhida e distingue duas exceções: separar o documento selecionado ou unir conjuntos compatíveis pelo mesmo cliente, estabelecimento, competência e código/versão da política. Ambas exigem motivo, são auditadas e invalidam aprovações afetadas; opções incompatíveis não são oferecidas;
- Envios passa a trabalhar com uma fila atual: versões substituídas do mesmo `GroupId` não se acumulam na jornada comum. Cada item mostra referência curta, cliente, competência e anexos, com busca e filtros; preparar, aprovar ou concluir preserva o item e a etapa selecionados;
- Relatórios combina dois eixos independentes — cliente e período — permitindo **todos**, mês, ano ou intervalo mensal. XLSX e PDF declaram a cobertura aplicada, e o PDF relaciona tipos e arquivos de todos os documentos do recorte, inclusive os que ainda não possuem mensagem;
- Histórico apresenta as ações em linguagem operacional, com cliente, competência, tipos e quantidades de documentos quando esse contexto estiver disponível no estado persistido; eventos antigos sem vínculo suficiente são identificados como contexto não disponível. A limpeza somente visual e recuperável permanece disponível por dia, hora ou intervalo em um seletor mais explícito;
- o cartão global de **Competência** foi reorganizado para manter rótulo, período, ano e mês alinhados também em largura reduzida;
- o ADR-0011 registra as fronteiras entre agrupamento, liberação por cliente, fila atual, filtros de relatório e auditoria recuperável;
- versão candidata 0.12.6: restore bloqueado, formatação, build Release com 0 warnings/0 erros e **296 testes** passaram — 29 Domain, 80 Application, 73 Infrastructure, 33 Server e 81 UI — e a auditoria transitiva não encontrou pacote vulnerável em nenhum projeto;
- três regressões adicionais de UI cobrem: voltar de Envios para Documentos realinha o conjunto ao documento; o evento agregado aparece em todas as competências registradas; retirar um documento preserva cliente, período e tipo no Histórico, enquanto uma tentativa antiga não entra no relatório corrente;
- o XLSX passou pela inspeção com `artifact_tool`, sem erro de fórmula e com a contagem correta de conjuntos; o PDF A4 de duas páginas foi renderizado e inspecionado integralmente, incluindo os documentos do recorte ainda sem mensagem;
- o pacote macOS arm64 `validation` 0.12.6 `final3` foi regenerado e instalado em `/Applications/Folhas da Michelly.app`; checksums e hashes de apphost, `FolhasDaMichelly.Desktop.dll` e `FolhasDaMichelly.Application.dll` coincidem entre pacote e instalação, a versão é `0.12.6.0` e a assinatura é ad-hoc, portanto continua não distribuível;
- o publish `win-x64` `final3` foi regenerado e o executável foi confirmado como PE32+; isso não substitui pacote assinado nem smoke em Windows físico;
- o smoke visual final do aplicativo macOS já instalado permaneceu pendente porque o Mac estava bloqueado; a CI da 0.12.6 foi posteriormente aprovada na execução `32793671723`;
- esta correção não inicia nova fase, não configura OAuth, não executa e-mail real, não publica stable e não faz deploy.

## Correção de fluxo, histórico e relatórios 0.12.5 após a Fase 12

- Documentos passou a explicar **conjunto para mensagem** como a unidade que seguirá junta e oferece três caminhos explícitos: liberar somente o conjunto selecionado, todos os conjuntos prontos do cliente ou todos os conjuntos prontos do recorte; erros, bloqueios e duplicidades permanecem excluídos;
- **Corrigir ou retirar o documento selecionado**, **Organização manual — somente se precisar separar ou unir** e **Como o aplicativo identificou este documento** iniciam recolhidos, preservando as exceções sem poluir o caminho principal;
- Envios foi reorganizado em sequência vertical de quatro passos — preparar, conferir destinatário/texto/anexos, aprovar e concluir — com mais espaço para a conferência e modo imutável depois da preparação; envio individual e conclusão conjunta têm confirmações separadas e coerentes com suas quantidades de mensagens/anexos;
- tipos documentais, estados e conteúdo visível em Envios usam terminologia portuguesa; `fake.local` declara honestamente a simulação local, que não envia mensagem nem comprova chegada;
- Histórico ganhou limpeza somente visual e recuperável por tudo, competência, dia, hora ou intervalo; eventos originais permanecem append-only e podem voltar à visualização;
- Relatórios aceita recorte por mês, cliente ou todos os períodos e gera XLSX, PDF e CSV com documentos ainda sem mensagem, pendências documentais e estados de comunicação; o seletor por cliente é carregado diretamente em Relatórios e preserva inativos/clientes históricos;
- aceitação técnica pelo serviço de e-mail é apresentada separadamente de entrega: `AcceptedByProvider` não é prova de chegada ao destinatário; a interface usa o provedor registrado em cada tentativa, não o provedor atualmente selecionado, e rascunho `fake.local` permanece contabilizado como simulação local;
- versão de correção: 0.12.5; restore bloqueado, format, build Release e **267 testes** passaram — 29 Domain, 65 Application, 72 Infrastructure, 33 Server e 68 UI — sem pacotes vulneráveis;
- a planilha teve as cinco abas renderizadas e inspecionadas sem erros de fórmula; o PDF A4 de duas páginas foi renderizado integralmente; o pacote `osx-arm64-beta validation` teve todos os SHA-256 aprovados e foi instalado em `/Applications/Folhas da Michelly.app`;
- o smoke final percorreu Documentos, Envios, Relatórios e Histórico na janela reduzida, confirmou as exceções recolhidas, a sequência vertical, os recortes independentes e a limpeza recuperável; nenhum relatório operacional do proprietário, cadastro ou e-mail real foi criado durante o smoke;
- esta correção não inicia nova fase, não adiciona OAuth/configuração externa, não executa provider real e não libera `Send`.

## Correção focal de UX 0.12.4 em Clientes e Documentos

- Clientes passou a ter duas camadas recolhíveis e independentes — lista e cadastro — com cabeçalhos e ações essenciais sempre visíveis; pelo menos uma camada permanece aberta para não deixar a área sem conteúdo útil;
- **Novo cliente** e a abertura de um cadastro direcionam o foco ao formulário e recolhem a lista automaticamente, liberando a largura e a altura disponíveis; **Mostrar lista** restaura a pesquisa sem perder o formulário;
- Documentos foi reduzido a uma jornada principal única: **Adicionar → Conferir → Liberar**. A tela usa uma única rolagem externa, de modo que detalhes, correções e ações finais continuam alcançáveis na janela mínima;
- a escolha de PDFs é a ação primária; a origem por pasta inteira e as operações excepcionais de separar/unir ficaram em revelação progressiva e não competem com a conferência cotidiana;
- cada linha documental mostra arquivo, tipo, competência, cliente reconhecido, CPF/CNPJ mascarado e situação; o detalhe explica se a identificação foi automática, manual ou não resolvida e como o documento será organizado por cliente, estabelecimento e competência;
- a interface não afirma associação quando o resolvedor não a comprovou. CNPJ/CPF elegíveis continuam determinísticos, CPF de sócio/empregado continua excluído e ambiguidade continua bloqueada para decisão humana;
- documentos bloqueados abrem diretamente a seção de correção/retirada; documentos prontos expõem a liberação sem exigir navegação por uma lista paralela de grupos. Separar/unir e evidências técnicas permanecem disponíveis somente em **Mais opções**;
- quatro testes de UI cobrem a exclusão mútua segura dos painéis, o foco automático no cadastro e as explicações de identificação/agrupamento resolvido ou bloqueado;
- versão elevada para 0.12.4; restore bloqueado, format, build Release e a matriz consolidada de 248 testes foram aprovados — 29 Domain, 56 Application, 70 Infrastructure, 33 Server e 60 UI — e a auditoria NuGet não encontrou pacote vulnerável;
- o pacote `osx-arm64-beta validation` 0.12.4 teve todos os SHA-256 conferidos, foi instalado em `/Applications/Folhas da Michelly.app` e passou por smoke real em janela mínima: lista/cadastro recolhíveis, uma única rolagem em Documentos e ações de correção/liberação acessíveis. Continua não assinado e não deve ser distribuído como stable;
- o SQLite foi copiado antes do smoke para `Library/Application Support/FolhasDaMichelly/CodexBackups`; o aplicativo 0.12.3 foi preservado no mesmo diretório de backups. O PDF operacional não rastreado do proprietário permaneceu intocado;
- esta entrega altera somente Clientes e Documentos, não inicia nova fase, não muda parser/agrupamento contábil, não provisiona serviços externos e não abre `Send` real.

## Correção operacional, cadastral e de UX 0.12.3 após a Fase 12

- a competência passou a ser uma preferência global única e idempotente: ano/mês permanecem iguais ao navegar e reiniciar, a importação multicompetência não a sobrescreve, a lista de anos não duplica valores e **um mês de todos os anos** filtra também o Histórico pelo mês escolhido;
- Clientes ganhou lista prioritária, abertura por clique, busca também com Enter, ocultação de CPF/CNPJ opcional e ações distintas para criar, atualizar, inativar, reativar e excluir da lista com preservação da auditoria;
- salvamento, inativação e reativação confirmam a alteração cadastral e a invalidação das aprovações documentais na mesma transação; falha de revalidação reverte cadastro/auditoria, e falha posterior de atualização da tela nunca deixa “Inativando/Reativando…” preso;
- cadastros legados com e-mail ativo incompleto abrem e podem ser reativados, mas ficam bloqueados para uso operacional até a correção; contatos históricos já inativos e inválidos não bloqueiam prontidão nem uma atualização não relacionada;
- novos e-mails exigem endereço e domínio completos; CPF de sócio/representante nunca disputa associação com uma pessoa física, e identificadores fiscais legados colocados no lugar errado são desativados sem apagar o histórico;
- CNPJ e CPF continuam validados somente pelos dígitos verificadores oficiais, sem consulta a Receita Federal, Serpro ou qualquer base externa;
- modelos de mensagem receberam predefinições gerais para empresa e pessoa física, inserção amigável de campos no assunto ou corpo, ativação visual e exclusão recuperável da lista;
- Documentos foi reorganizado em **Adicionar PDFs → Corrigir o que falta → Aprovar para mensagem**, com origem por arquivos/pasta, filtro global, indicação acionável de pendências, aprovação individual/lote separadas e retirada da revisão para repetir testes sem apagar o PDF do acervo;
- o reconhecedor permanece deliberadamente limitado a PDF: ao ler uma pasta mista, importa os PDFs e resume por extensão quantos DOCX, XLSX ou outros arquivos foram recusados e preservados, sem classificação silenciosamente incorreta;
- Envios agora declara a origem em Documentos e distingue preparar, conferir, aprovar e concluir; ações de lote consideram todos os itens elegíveis da competência, independentemente do item individual selecionado, e a simulação local informa que não envia nem comprova chegada;
- Relatórios exige um único mês/ano e gera planilha profissional com resumo executivo, itens, erros, duplicados e auditoria, além de filtros, painéis congelados, datas tipadas, colunas técnicas recolhidas, CSVs e proteção contra fórmulas; Gmail/Graph sem tentativa são identificados pela conta configurada, sem serem falsamente rotulados como simulação ou enviados;
- Histórico prioriza ações em português e recolhe ocorrências/detalhes de suporte; identificadores de ator e a palavra técnica `snapshot` deixaram a apresentação comum;
- Configurações foi reorganizada em contas de e-mail, cópia de segurança, pastas, atualização, retenção e detalhes técnicos. A tela explica exatamente o que o `.fdmbackup` inclui e o que continua exigindo cópia separada do acervo;
- o registro oficial de Gmail/Microsoft 365 continua obrigatório antes de liberar os botões: nenhuma senha, token, OAuth live, conta externa ou mensagem real foi usada nesta correção;
- versão elevada para 0.12.3; restore bloqueado, format, build Release e a matriz consolidada de 244 testes foram aprovados — 29 Domain, 56 Application, 70 Infrastructure, 33 Server e 56 UI — e a auditoria NuGet não encontrou pacote vulnerável;
- o pacote `osx-arm64-beta validation` 0.12.3 teve todos os SHA-256 conferidos, foi instalado em `/Applications/Folhas da Michelly.app` e passou por smoke real: competência única/persistente, busca por Enter, abertura de legado, inativação/reativação, sete áreas e janela reduzida. Continua não assinado e não deve ser distribuído como stable;
- backups prévios do SQLite e do aplicativo 0.12.2 foram preservados fora do repositório. O PDF operacional não rastreado do proprietário permaneceu intocado;
- esta entrega não inicia Fase 13, não adiciona reconhecimento de Word/planilha, não provisiona Server/PostgreSQL e não abre `Send` real.

## Correção operacional e de UX 0.12.2 após a Fase 12

- o cadastro passou a distinguir criação de atualização: cliente aberto usa **Atualizar cadastro**, inativação/reativação persiste imediatamente e o registro permanece recuperável na lista com estado visível;
- tentar cadastrar CPF/CNPJ que já pertence a um registro inativo reabre esse cadastro para correção ou reativação, em vez de sugerir exclusão ou criar duplicidade; CPF/CNPJ continuam sujeitos à validação matemática oficial, inclusive quando digitados sem pontuação;
- o editor de modelos separa a edição do item selecionado, aceita corpo de até 20.000 caracteres, mostra contadores e oferece inserção por rótulos amigáveis; somente placeholders do catálogo público são aceitos, e chaves desconhecidas bloqueiam o salvamento com orientação;
- cada documento bloqueado passou a expor a pendência e a próxima ação: reativar cliente inativo, escolher associação alternativa permitida, corrigir competência ou voltar ao período reconhecido no PDF;
- a correção de competência é persistida em `PeriodOverride`, exige justificativa, revalida duplicidade/agrupamento e revoga aprovação incompatível; restaurar o período extraído remove o override e executa a mesma revalidação;
- **Retirar da revisão** usa confirmação em duas etapas, remove logicamente o item do workspace e registra auditoria, sem apagar o PDF físico do acervo; grupos que ficarem vazios são removidos automaticamente, inclusive ao carregar estado legado;
- o perfil local passou a usar `LocalDesktopOperationContextAccessor`: concede somente processamento de documentos, aprovação em lote, criação de rascunho e exportação de relatório local, nunca `email.send`; o perfil conectado continua derivando organização, ator e permissões do token da sessão e falha sem permissões quando não autenticado;
- as telas de Clientes, Documentos, Envios e Configurações receberam estados vazios, ações condicionais, detalhes progressivos, hierarquia tipográfica e dimensões mínimas mais coerentes para reduzir corte e desalinhamento em janelas menores;
- a recuperação de CPF/CNPJ existente passou a pré-carregar cadastro e lista antes de alterar a tela; falhas de rede/leitura preservam o rascunho e exibem orientação, sem exceção solta;
- alternar o estado de um modelo durante a edição mantém versão/atividade sincronizadas, e Envios só permite repetir um item `Failed` quando a última tentativa do mesmo fingerprint foi comprovadamente transitória;
- Configurações agora explica que cadastro, PDFs, revisão e rascunho local não exigem Server/PostgreSQL; Server é a API para uso compartilhado, PostgreSQL é a autoridade central acessada somente pela API e token é a credencial temporária de sessão guardada no cofre nativo, nunca no Git;
- a conexão Gmail/Outlook permanece desabilitada até existir registro oficial do aplicativo em Google Cloud/Microsoft Entra, Client ID e conta controlada; nenhuma senha, token, OAuth live ou mensagem real foi usada nesta correção;
- versão elevada para 0.12.2; restore bloqueado, format, build Release e a matriz consolidada de 197 testes foram aprovados — 21 Domain, 54 Application, 54 Infrastructure, 33 Server e 35 UI;
- o pacote macOS arm64 0.12.2 de validação teve seus hashes verificados, foi instalado em `/Applications/Folhas da Michelly.app` e passou por smoke visual em Início, Clientes, Documentos, Envios e Configurações, inclusive em janela menor; o pacote permanece não assinado e não deve ser distribuído como stable;
- esta entrega preserva integralmente o histórico 0.12.1, não inicia Fase 13, não provisiona serviços externos e não abre `Send` real.

## Correção operacional e de UX 0.12.1 após a Fase 12

- o Desktop passou a usar catálogo e resolvedor SQLite por padrão quando `Phase2:ApiBaseAddress` não está configurado; cadastro, busca, abertura, inativação, modelos, backup e reconhecimento de cliente funcionam sem iniciar o Server;
- configurar explicitamente a URI da API preserva o perfil conectado HTTP/PostgreSQL, sem fallback de escrita, merge ou migração silenciosa para o catálogo local;
- registros autoritativos locais usam namespace SQLite próprio (`local-client`, `local-message-template` e auditoria local), separado do cache somente leitura do perfil conectado;
- o catálogo local aplica as mesmas validações de domínio, unicidade, concorrência otimista, prontidão e auditoria redigida; export/import mantém prévia `DryRun` e confirmação;
- a revisão documental pode resolver novamente o cliente após o cadastro local, incluindo CNPJ exato e raiz CNPJ coerente com nome, sem usar CPF de sócio como identificador;
- editar/inativar um cliente revalida as associações existentes, revoga snapshots incompatíveis e nunca transfere silenciosamente um documento para outro cliente;
- o formulário de cliente ganhou validação antecipada, feedback inline de erro/sucesso, atualização imediata da lista, ação Salvar também ao final e mensagens em linguagem operacional;
- abrir e salvar um cliente preserva os modelos padrão de assunto e corpo já escolhidos, evitando mudança silenciosa da mensagem;
- sócio/representante aceita e-mail opcional validado e pode ser usado também como contato de entrega; a migration central `AddPartnerOptionalEmail` mantém o perfil conectado compatível;
- a importação reconhece a competência antes de copiar o arquivo ao acervo, organiza cada PDF em `AAAA/MM`, deduplica por SHA-256, seleciona um item importado e exibe todos os períodos quando o lote mistura competências;
- Documentos preserva a competência escolhida, avisa quantos itens ficaram fora do filtro e oferece **Mostrar todos**; Início usa atalhos mais específicos e Envios agrupa aprovação, conclusão, reconciliação e continuidade de forma mais legível;
- aprovação, preparação e conclusão em lote exigem uma única competência mensal; a aprovação mensal é atômica, persiste uma vez e nunca aprova grupos fora da seleção; lotes antigos que misturem períodos são bloqueados e pedem processamento individual ou recomposição;
- o Desktop mantém uma única instância por conta de usuário para impedir concorrência destrutiva sobre o SQLite; falha/cancelamento após copiar um PDF desfaz a cópia antes de informar a rejeição;
- navegação, cabeçalho, competência, cartões, rodapé, fontes, datas locais, imagens e tamanhos mínimos foram realinhados; booleanos técnicos `True` saíram das listas;
- Configurações explica o modo de simulação sem e-mail real, mostra Google/Microsoft com ações habilitadas somente para o provider configurado, simplifica atualização/backup e adiciona revisões de retenção em 2, 3 e 6 meses sem exclusão automática;
- piloto agora é desativado por padrão; piloto e produção fechada ficam fora da jornada comum e permanecem explicados nos detalhes técnicos;
- versão elevada para 0.12.1 e ADR-0010 registra catálogo local padrão versus modo conectado explícito, complementando o ADR-0002;
- bundle `osx-arm64` 0.12.1 de validação foi empacotado com checksums, instalado em `/Applications/Folhas da Michelly.app` e conferido byte a byte contra o pacote; a versão anterior permanece em `artifacts/install-backups`;
- o caminho default totaliza 175 testes aprovados, sem OAuth, Graph/Gmail live, publicação ou envio real;
- esta entrega não inicia Fase 13 nem altera os limites da Fase 12: `fake.local`, produção `Closed` e ausência de `Send` real permanecem obrigatórios.

## Entregas da Fase 12

- versão elevada para 0.12.0 e status não sensível do serviço atualizado para `Phase12GradualProduction`;
- `ProductionReadinessEvaluator` exige ambiente production, estágio Limited/Gradual, aceite do piloto, F11 desligada, stable, backup/restore, monitoramento, resposta a incidentes, suporte, limites e versão válidos;
- a fiscalização F12 é obrigatória para `Send` externo e não pode ser desativada por appsetting;
- interface remove `Send` enquanto a prontidão estiver falsa e mostra estágio, limites, papéis e seis gates em painel somente leitura;
- workflow rejeita `Send` externo antes da composição e novamente antes da execução, inclusive por limite do lote;
- preflight central combina provider/F10/F11/F12, versão máxima, papel privilegiado, MFA, sessão, cota por organização e transação serializável;
- autorização append-only por `(OrganizationId, OperationId)` é idempotente para o mesmo fingerprint e recusa reutilização divergente sem expor conteúdo/destinatário na auditoria;
- endpoint `GET /api/production/policy` exige `email.send` + MFA e informa apenas estágio, limites, papel da sessão, contagens agregadas e bloqueadores;
- migration `AddPhase12ProductionRollout` cria a tabela e índices de cota UTC por organização/usuário;
- `appsettings.Production.json`, `deploy/production/.env.example` e `tools/production/verify-readiness.sh` foram adicionados fechados e sem segredo;
- ADR-0009 e `docs/PRODUCTION.md` definem abertura Limited, ampliação Gradual, kill switch, parada, restauração e riscos sem declarar gates externos como executados;
- 8 testes adicionais cobrem prontidão, bloqueio pré-composição, UI sem Send, autenticação/papel, cota, lote, idempotência e política central.

## Entregas da Fase 11

- versão do aplicativo elevada para 0.11.0 e status do serviço para `Phase11SupervisedPilot`;
- `PilotModeOptions` limita a homologação a staging, cinco clientes, dados não produtivos e apenas Test/Draft por padrão;
- a interface de Envios remove Send no piloto e explica a salvaguarda em linguagem operacional;
- o workflow rejeita Send antes de criar composição/lote e repete a validação imediatamente antes de executar um item recuperado;
- o preflight central passou a receber o modo e aplica F11 sobre as travas de provider/F10: Test controlado pode ser autorizado e Send retorna `PILOT_SEND_DISABLED`;
- endpoint autenticado `GET /api/pilot/policy` divulga somente ambiente, modos, limite e versão; configuração inválida retorna 503;
- Configurações ganhou painel alinhado de piloto com seis confirmações, progresso, situação e métricas agregadas, sem nomes, caminhos, destinatários ou conteúdo;
- checklist persiste no SQLite por hash do escopo organizacional, recupera após restart e preserva evento append-only ao marcar ou revogar cada confirmação;
- prontidão é revogada por checklist incompleto, configuração insegura, mais de cinco clientes, tentativa Send, falha/ambiguidade ou ocorrência alta/crítica;
- `appsettings.Staging.json`, template `deploy/staging/.env.example` e verificador read-only de health/política foram adicionados sem segredo;
- ADR-0008 e `docs/PILOT.md` definem dados aceitos, roteiro contábil, checklist macOS/Windows, métricas, limites de parada e rollback forward-only;
- 9 testes adicionais cobrem bloqueio antes da composição, disponibilidade Test/Draft, avaliação/auditoria do checklist, persistência/isolamento, política autenticada/fail-closed e UI sem Send.

## Entregas da Fase 10

- Velopack 1.2.0, estável/MIT e com ativo `net10.0`, foi fixado no gerenciamento central e no manifesto de ferramentas; a UI depende somente de `IAppUpdateService`;
- `VelopackApp` inicia antes do host/UI e o auto-apply foi desligado: verificar, baixar e reiniciar são três decisões explícitas;
- feeds são separados em `osx-arm64-beta`, `osx-arm64-stable`, `win-x64-beta` e `win-x64-stable`; downgrade automático é proibido;
- URL vazia, HTTP, credencial/query/fragmento ou plataforma não autorizada falham fechados; caminho local requer opt-in de validação;
- falha de checksum/corrupção não aplica update; verificador SHA-256 usa comparação em tempo constante e tem teste de adulteração sintética;
- Configurações mostra versão, Estável/Beta e estados manuais em linguagem do cliente; a inspeção visual removeu termos de CI/assinatura da área comum;
- preferências guardam apenas o canal local; trocar o canal invalida atualização pendente;
- scripts self-contained geram instalador, portable, pacote completo, metadata e `SHA256SUMS.txt`; stable sem assinatura e diretório já ocupado são recusados;
- validation recebe marcador explícito não distribuível; modo signed exige secrets e valida Authenticode no Windows ou assinatura/notarização/Gatekeeper no macOS;
- workflow `release.yml` é somente manual, neutraliza inputs no shell, usa environments protegidos para beta/stable e apaga o keychain/P12 temporário;
- API autenticada expõe versão mínima e switches de beta/stable/Send; preflight Graph/Gmail aplica o mínimo global e o kill switch global;
- `docs/RELEASES.md` define promoção beta → stable, publicação separada, feed sem token pessoal e rollback como reparação forward-only com SemVer maior;
- pacotes reais `osx-arm64-beta validation` 0.10.0–0.10.2 foram gerados durante a evolução dos scripts; hashes, isolamento por versão, conteúdo arm64 e versão dinâmica foram conferidos, e os bundles 0.10.0/0.10.2 abriram/fecharam em smoke local sem OAuth/envio;
- 18 testes adicionais cobrem UI manual/canal, compatibilidade de preferência, feed fail-closed, adulteração, automação de release, política autenticada/versão, kill switch global e regressões.

## Entregas da Fase 9

- ADR-0007 e `docs/THREAT_MODEL.md` registram ativos, fronteiras, ameaças, controles, riscos residuais e critério para dados reais;
- API usa limites por autenticação/leitura/escrita/sensível, particionados por usuário/origem, com limite global, fila zero, 429 genérico, `Retry-After` e correlação opaca;
- autenticação ocorre antes do limiter; auth sintética fora de `Testing` aceita apenas loopback; MFA inválido conta no lockout;
- CSP, no-frame, no-sniff, Permissions-Policy, no-store em identidade, corpo máximo de 12 MB e tempos Kestrel foram adicionados;
- produção recusa `AllowedHosts=*` e carrega PFX distintos para assinatura/criptografia OIDC, com chave privada e validade mínima de 30 dias;
- redator remove e-mail, CPF/CNPJ, bearer/JWT, senha e tokens antes de relatos persistidos;
- incidentes locais vinculam tentativa/lote/item/grupo, têm categoria/gravidade, versão otimista, transições controladas, justificativa de resolução e auditoria append-only isolada por escopo;
- migration `AddPhase9Incidents` adiciona `incidents` e `incident_audit`; restart SQLite e isolamento foram testados;
- cópia do catálogo saiu de JSON claro para `.fdmbackup` AES-256-GCM, com PBKDF2-HMAC-SHA-256/600 mil iterações, senha não persistida, limite e falha genérica em senha/adulteração;
- Configurações declara que PDFs/tokens ficam fora da cópia, exige prévia antes do restore e oferece análise de retenção somente leitura, sem exclusão automática;
- diretório local próprio recebe permissões restritas em Unix; FileVault/BitLocker e backup separado do acervo continuam requisitos para dados reais;
- Documentos ganhou progresso/cancelamento cooperativo; histórico limita visões a 500 itens; `Ctrl+1`–`Ctrl+7`, heading, nomes de automação e regiões vivas ampliam o uso por teclado/leitor de tela;
- interface de Histórico ganhou jornada “Relatar → Conter/Apurar → Resolver/Encerrar” com linguagem orientada ao escritório.

## Reformulação de UX anterior à Fase 8

- navegação central por abas foi substituída por menu lateral permanente: Início, Clientes, Documentos, Envios, Relatórios, Histórico e Configurações;
- tela inicial passou a explicar a sequência de trabalho e oferecer atalhos, sem expor fase, provider, framework ou termos de implementação;
- cadastro foi reunido em um formulário contínuo com dados essenciais e contato de entrega; filiais, aliases/códigos e mensagem personalizada permanecem opcionais e explicados;
- estados, modos, tipos documentais, severidades, papéis de contato e ações de auditoria receberam rótulos em português orientados ao usuário;
- documentos agora seguem a jornada Importar → Conferir → Aprovar, com correções e evidências recolhidas como detalhes excepcionais;
- envios agora seguem Preparar → Conferir → Aprovar → Concluir, com conta de e-mail movida para Configurações e cenários fake restritos ao suporte;
- import/export textual por JSON saiu da interface comum; cópia de segurança usa arquivo, prévia obrigatória e confirmação de restauração;
- `logo1.png`, `aaa1.png` e `aaa2.png` foram autorizados, versionados em `Assets/Branding` e incorporados ao aplicativo para todas as estações e clientes;
- paleta passou a usar conteúdo claro, navegação escura e dourado moderado, preservando contraste, texto junto da cor e rolagem por área;
- QA visual em bundle temporário macOS percorreu Início, Clientes, Documentos, Envios e Configurações com as três imagens oficiais;
- decisão e racional completos registrados em `docs/UX_AND_BRANDING.md`; naquela entrega prévia, a Fase 8/Gmail permaneceu intocada e só foi autorizada depois.

## Segunda revisão de UX anterior à Fase 9

- ícones laterais passaram de glifos variáveis para vetores uniformes, corrigindo alinhamento de Início/Clientes e padronizando texto/área clicável;
- cabeçalho alinha título, “Ambiente protegido” e competência global de ano/mês;
- Início, Documentos e Envios receberam passos equidistantes, origem explícita e ações de continuidade Documentos → Envios → Relatórios;
- busca de clientes mantém Novo/Abrir no topo; inativação só aparece para cadastro existente;
- editor condicional separa PF (CPF/nome completo) de PJ (CNPJ/razão social/nome fantasia/código), com máscara de digitação e validação normalizada;
- sócios e representantes PJ têm papel/CPF opcionais em coleção própria; CPF de sócio não entra no resolvedor do cliente;
- pasta inteira pode ser importada com ou sem subpastas; PDFs são copiados para acervo local `AAAA/MM` e repetidos continuam bloqueados por hash/chave semântica;
- competência filtra documentos, grupos, lotes, mensagens, histórico e relatórios; preferências persistem localmente sem token;
- Configurações reúne Google Gmail e Outlook/Microsoft 365, manter sessão, entrada, acervo, relatórios e backup em cartões alinhados;
- `logo1.png` agora gera ICO/ICNS e foi verificada como ícone real do bundle no Finder do macOS;
- migrations `AddPrePhase9ClientPartners` e `AddPrePhase9WorkspacePreferences` criadas; decisão registrada no ADR-0006;
- QA macOS percorreu Início, Clientes, Documentos, Envios, Relatórios e Configurações; a Fase 9 não foi iniciada.

## Ambiente confirmado

| Item | Resultado real em 2026-08-21 |
|---|---|
| Host | macOS 26.5.2, Apple Silicon `arm64` |
| .NET | SDK 10.0.400; runtime 10.0.11 |
| PostgreSQL | 18.6 arm64; banco descartável F3 removido e serviço de gate encerrado |
| Git/GitHub CLI | Git 2.54.0; `gh` 2.97.0 autenticado como `danziatus` |
| Repositório | privado, `origin` configurado, branch `main` |
| Xcode | somente Command Line Tools; `notarytool` existe, mas Xcode completo e identidades de assinatura estão ausentes; bloqueia gates assinados F10 |

## Entregas da Fase 8

- `google.gmail` foi adicionado sem alterar o default `fake.local`; configuração desconhecida ou incompleta falha fechada;
- OAuth para aplicativo instalado usa navegador do sistema, loopback IPv4 com porta efêmera e PKCE S256, sem client secret, senha ou fluxo OOB;
- somente `gmail.compose` é solicitado; escopos amplos de leitura/modificação da caixa foram recusados por desenho;
- access/refresh token, expiração, escopo e conta ficam na chave Google própria do `ISecretStore` nativo, separados de MSAL e da sessão do aplicativo;
- refresh revogado remove o cache e retorna `AUTH_REVOKED`; desconectar tenta revogação remota e sempre encerra a sessão local;
- Gmail API v1 usa `HttpClient`; MimeKit 4.17.0 (MIT, estável e mantida) produz MIME UTF-8 com texto, HTML e PDFs;
- o SDK `Google.Apis.Gmail.v1` foi avaliado e deliberadamente não instalado: o subconjunto REST é pequeno e o adaptador próprio preserva cofre/retry explicitamente testáveis;
- todo Gmail força um destino controlado, zera Cc, preserva a rota original apenas no snapshot e usa prefixo `FASE 8 — DESTINO CONTROLADO`;
- Test/Send exigem `email.send`, aprovação, versão mínima e kill switches local/remoto independentes dos controles Graph; Send pede `CONFIRMAR GMAIL N` ou `CONFIRMAR GMAIL LOTE G N`;
- API central aceita `google.gmail`, usa configuração `Phase8:Gmail`, exige MFA/sessão e grava preflight redigido sem destino/fingerprint;
- fluxo draft-first usa `Message-Id` estável e busca de draft com `gmail.compose`, relê tamanho/SHA-256 e envia somente o ID do draft;
- GET idempotente pode respeitar `Retry-After`; criação e Send nunca recebem retry cego;
- timeout de Send vira `Ambiguous`; draft ainda existente prova “não enviado”, enquanto draft ausente permanece desconhecido em vez de presumir entrega;
- Configurações do Desktop mostram Google Gmail/Microsoft 365/modo local em linguagem do usuário e abrem o navegador somente quando o provider está configurado;
- QA visual em bundle temporário macOS confirmou a tela Google Gmail desconectada, explicação sem senha, Conectar disponível e Desconectar inativo, sem iniciar OAuth;
- suíte `GmailLive` retorna sem rede por padrão, cria/remove draft sintético com opt-in e exige uma segunda variável para Send;
- 10 testes offline novos cobrem política Gmail, OAuth/PKCE/state/cofre/revogação, MIME/anexo, destino, IDs, ambiguidade, confirmação e preflight central.

## Entregas da Fase 7

- `MicrosoftGraphEmailProvider` em Infrastructure usa somente Graph v1.0 via `HttpClient`; Domain/Application não dependem de SDK Microsoft;
- única dependência nova: Microsoft.Identity.Client 4.88.0, estável, MIT, publicada/mantida pela Microsoft e fixada com lockfile;
- OAuth delegado em public client/navegador do sistema, separado do login do app e sem client secret/senha;
- escopos mínimos separados: `Mail.ReadWrite` para draft/reconciliação e `Mail.Send` somente quando o kill switch Graph está habilitado antes da conexão;
- `ISecretStore` de runtime substituiu memória por Keychain no macOS e arquivo DPAPI CurrentUser no Windows; cache MSAL usa chave separada;
- UI permite conectar/desconectar conta Microsoft e registra eventos locais redigidos de auditoria;
- Fake continua default; Graph exige provider explícito, Enabled, ClientId GUID, tenant, redirect e único destinatário controlado;
- todo Graph força o destino controlado, remove Cc, preserva rota original apenas no snapshot e prefixa o assunto;
- toda saída Graph (`Test`/`Send`) exige `email.send`, aprovação atual, versão mínima, kill switches local/remoto e preflight autenticado com MFA/sessão ativa e auditoria central redigida; API/banco indisponível ou resposta incoerente bloqueia fechado;
- Send exige adicionalmente a frase `CONFIRMAR GRAPH N`/`CONFIRMAR GRAPH LOTE G N`; Test permanece exclusivo da caixa controlada;
- fluxo draft-first com propriedade estendida idempotente e `Prefer: IdType="ImmutableId"`; o mesmo ID permite reconciliar Drafts/Sent Items;
- anexos menores usam `fileAttachment`; anexos maiores usam upload session HTTPS/chunks; tamanho e SHA-256 são relidos antes da saída;
- 202 é `AcceptedByProvider`, nunca `Delivered`; timeout/5xx desconhecido vira `Ambiguous` e Send não recebe retry cego;
- GET/chunk idempotente respeita `Retry-After` ou backoff exponencial+jitter; falhas 401/403/429 recebem códigos funcionais redigidos;
- reconciliação de cópia enviada fecha como aceite; draft ainda não enviado permite retomada somente após nova confirmação humana;
- suíte live marcada/opt-in cria e remove draft sintético; Send exige segunda variável explícita e não foi executado;
- 16 testes novos cobrem configuração/escopos, política Graph, preflight/RBAC/MFA/auditoria central, indisponibilidade, conexão local, Keychain real, destino, draft/send, anexos, revogação, throttling, ambiguidade e reconciliação.

## Entregas da Fase 6

- workflow individual e em lote limitado a grupos documentais já aprovados; bloqueados não entram na seleção em lote;
- compositor determinístico com whitelist dos nove placeholders normativos, linguagem neutra e blocker para token desconhecido;
- snapshot separado da composição com versões dos templates, destinatários, corpo, assunto, anexos e fingerprint SHA-256 canônico;
- Test aceita somente `example.invalid`, prefixa o assunto, remove Cc efetivo e preserva destinatários originais para conferência/relatório;
- Draft e Send são estritamente simulados por `FakeEmailProvider`; não há SDK, OAuth, endpoint ou tráfego Gmail/Graph;
- Send simulado exige `email.send`, kill switch ativo, versão mínima e confirmação digitada `CONFIRMAR N`;
- tentativa SQLite é persistida como `Pending` antes do provider; resultado é salvo imediatamente e idempotency key é única por organização;
- timeout e resultado ambíguo bloqueiam nova preparação/execução e exigem reconciliação sem repetir a chamada;
- provider local grava recibos JSON atômicos e simula success, transient, permanent, timeout e ambiguous com IDs fake;
- tabelas SQLite `processing_batches`, `dispatch_items`, `delivery_attempts` e `dispatch_audit`, com migration `AddPhase6DispatchWorkflow`;
- relatórios XLSX/CSV com Resumo, Itens, Erros, Duplicados e Auditoria, filtros, panes congelados, legenda/status e proteção contra formula injection;
- UI Avalonia com modos, cenário fake, preview, destinos original/efetivo, bloqueios, aprovação, confirmação, reconciliação e exportação;
- ClosedXML 0.105.1 confinada a Infrastructure, licença MIT e estabilidade/manutenção verificadas antes da inclusão;
- 12 testes novos cobrem individual/lote, três modos, destino Test, alteração de template, kill switch/versão mínima, ambiguidade, restart SQLite, provider e relatórios.

## Entregas acumuladas da Fase 5

- `DocumentPeriod` tipado para competência mensal/anual, apuração, intervalo, evento e vencimento, sem reduzir todos os documentos a `MM/AAAA`;
- perfis versionados configuram campos obrigatórios, valor positivo, componentes de duplicidade e política de agrupamento;
- regras independentes para integridade/hash, reconhecimento, cliente, campos obrigatórios, período, total, raízes CNPJ e vencimento;
- severidades `Info`, `Warning`, `Error` e `Blocker`; `Error`/`Blocker` não resolvidos são excluídos da aprovação;
- duplicidade exata por organização+SHA-256 e semântica por cliente+tipo+período+identidade do perfil; duplicados ficam sem grupo;
- agrupamento mensal compatível e eventos individuais, com split/merge auditável e rejeição entre cliente/período incompatível;
- override aceita somente alternativa autenticada, exige justificativa, revalida tudo e revoga aprovação anterior;
- snapshot guarda revisão/hash canônico do grupo e documentos; arquivo, cliente, findings ou composição alterados o invalidam;
- SQLite recuperável e isolado por claim de organização em `document_reviews`, `document_review_groups` e `document_review_audit`;
- UI Avalonia com estado textual, findings, grupos, aprovação individual/em lote, override, split/merge e auditoria local;
- parser do 13º deduplica cabeçalhos iguais, preserva inscrições distintas e permite bloquear raízes de empregadores diferentes;
- migration EF `AddPhase5DocumentReview` e documentação `VALIDATION_AND_GROUPING.md`.

## Entregas acumuladas da Fase 4

- ingestão local por seletor múltiplo ou drag-and-drop, validação de extensão, assinatura MIME `%PDF-`, tamanho, páginas, caracteres, timeout cooperativo e cancelamento;
- SHA-256 streaming e cache SQLite versionado por hash/versão do motor, sem armazenar o PDF ou seu caminho absoluto;
- PdfPig 0.1.15 fixado atrás de `IPdfTextExtractor`/`IDocumentExtractor`, com texto, página e coordenadas de evidência;
- classificação determinística e parsers contextuais para férias, FGTS Digital, folha multipágina, DARF, 13º, pró-labore e rescisão;
- papéis semânticos distintos para empregador/cliente/estabelecimento, empregado, sócio, sindicato e emissor;
- resolvedor central tenant-safe na ordem exata CNPJ/estabelecimento, raiz+nome, CPF de cliente PF, código, razão social e alias; fuzzy apenas sugere;
- minimização de dados na chamada: o servidor recebe somente campos elegíveis de cliente e evidência mascarada, nunca o PDF, valor, CPF de empregado/sócio ou CNPJ de sindicato;
- raiz CNPJ ambígua bloqueia; sindicato/empregado/sócio não resolvem cliente mesmo aparecendo antes;
- texto vazio produz `NeedsOcr` e bloqueio explícito, sem OCR automático;
- UI de revisão mostra tipo, confiança, cliente proposto, campos semânticos e evidência por página;
- sete fixtures PDF determinísticas com `.txt` e `.json`, todas marcadas `DADOS SINTETICOS - SEM VALIDADE`;
- migration EF `AddPhase4DocumentRecognitionCache` e ADR-0005 aceito.
- correção do contexto OIDC Desktop para chaves `Guid`, validada por inicialização real do aplicativo no macOS.

## Gates locais executados

Os comandos foram executados no workspace; publish, banco SQLite de migration gate e relatório visual usaram diretórios temporários isolados.

| Gate | Resultado |
|---|---|
| `dotnet restore --locked-mode` | aprovado para 11 projetos |
| `dotnet format ... --verify-no-changes` | aprovado |
| `dotnet build -c Release --no-restore` | aprovado; 0 warnings, 0 erros |
| `dotnet test -c Release --no-build --no-restore` | 327 aprovados no caminho default, 0 falhas: 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI; suítes Graph/Gmail live permaneceram fora do caminho default sem opt-in |
| Golden tests PDF | sete perfis, evidência por página, folha multipágina e cabeçalhos repetidos do 13º aprovados |
| PDFs adversariais | MIME divergente, corrompido, limite de páginas, cancelamento e texto vazio/`NeedsOcr` aprovados |
| Resolução contábil | sindicato/empregado ignorados, tenant isolado e raiz CNPJ ambígua bloqueada |
| Minimização | somente campos elegíveis e evidência mascarada chegam ao endpoint de resolução |
| Smoke Desktop macOS | host, OIDC em memória e migrations SQLite iniciaram; encerramento manual limpo |
| Smoke visual Gmail macOS | bundle temporário abriu Início e Configurações; conta Google configurada/desconectada e assets oficiais renderizaram sem iniciar OAuth/rede |
| Smoke visual UX pré-Fase 9 | bundle temporário percorreu seis telas; alinhamentos, fluxos, pastas, competência e cartões Google/Microsoft conferidos; Finder exibiu `logo1.png` como ícone do app |
| Smoke visual Fase 9 | app Release real percorreu Início, Histórico/Ocorrências, Configurações/backup e Documentos; árvore de acessibilidade expôs labels/heading/regiões e `Ctrl+6` navegou somente por teclado; nenhum OAuth/envio acionado |
| Smoke visual Fase 11 | app Release 0.11.0 percorreu Envios e Configurações; seletor mostrou somente Teste/Rascunho, painel/checklist ficou alinhado em 1211×768 e estado permaneceu “Em preparação”; sem OAuth, marcação ou operação de mensagem |
| Smoke visual Fase 12 | bundle validation 0.12.0 abriu em Apple Silicon; Configurações exibiu `Etapa fechada`, seis gates, limites/papéis e aviso somente leitura alinhados; Envios ofereceu apenas Teste/Rascunho, sem OAuth/operação externa |
| Smoke correção 0.12.1 | instalação local abriu nativamente em Apple Silicon; Início, Documentos, validação vazia de Clientes, Envios e Configurações foram percorridos sem gravar cadastro nem executar mensagem; o bundle final foi relançado após a instalação, com navegação, alinhamento, rolagem, estados do fluxo, alertas e contas de e-mail indisponíveis validados visualmente |
| Smoke correção 0.12.2 | instalação macOS arm64 percorreu Início, Clientes, Documentos, Envios e Configurações, inclusive em janela menor; nenhuma conta externa foi conectada |
| Smoke correção 0.12.3 | instalação final percorreu as sete áreas; competência AGOSTO/2026 sobreviveu à navegação/reinício e a lista de anos ficou única; busca por Enter, abertura por clique e inativação/reativação de cadastro legado foram observadas; janela mínima preservou ações com rolagem; pacote final arm64 abriu novamente e a lista documental exibiu linguagem operacional sem “snapshot” |
| Smoke correção 0.12.4 | instalação final foi redimensionada à janela mínima; **Novo cliente** recolheu a lista e deixou o formulário acessível; Documentos manteve uma única rolagem externa e permitiu alcançar correção, retirada, liberação e detalhes progressivos sem corte; nenhum cadastro, documento ou envio foi executado |
| Correção 0.12.5 | 267 testes verdes; XLSX de cinco abas e PDF A4 de duas páginas renderizados; pacote macOS arm64 com hashes íntegros instalado e smoke de Documentos, Envios, Relatórios e Histórico aprovado sem envio real |
| Correção 0.12.6 | restore bloqueado, format, build Release 0/0 e 296 testes verdes; auditoria NuGet sem vulnerabilidades e QA XLSX/PDF aprovados; artefatos `final3` macOS/Windows regenerados; smoke visual final do app instalado pendente porque o Mac está bloqueado |
| Manutenção 0.12.7 | restore bloqueado, format, build Release 0/0 e 327 testes verdes; auditoria NuGet sem vulnerabilidades; QA XLSX/PDF e smoke visual em 1170×768 e 960×640 aprovados; CI tripla `32873335418` e pacotes `validation` `32873883491` aprovados |
| SQLite | migrations aplicadas; cache sem PDF e revisão/grupos/auditoria recuperáveis, isolados por organização |
| Workflow SQLite | lote/composição/tentativa/auditoria recuperam; `Pending/Ambiguous` continuam bloqueando retry após restart |
| Incidentes SQLite | ocorrência/versionamento e auditoria append-only recuperam após restart; escopos isolados e duplicidade ativa bloqueada |
| Backup protegido | round-trip AES-GCM aprovado; sem texto claro; senha errada e adulteração falham com a mesma resposta genérica |
| API hardening | 11ª tentativa/minuto bloqueada com 429/Retry-After; correlação normalizada e cabeçalhos de segurança aprovados |
| FakeEmailProvider | success/failure/timeout/ambiguous, recibo idempotente e reconciliação aprovados sem dependência de rede |
| Keychain macOS | round-trip e remoção de segredo sintético aprovados; nenhum valor gravado em arquivo |
| DPAPI Windows | round-trip e remoção CurrentUser aprovados no runner Windows; smoke do app empacotado em estação física ainda pendente |
| Graph mock | destino controlado, draft-first, anexo simples/grande, 202, `Retry-After`, revogação e reconciliação aprovados |
| Preflight central | permissão `email.send`, MFA, sessão/dispositivo, kill switch remoto, versão, auditoria redigida e fail-closed offline aprovados |
| Política de produção | papel privilegiado, versão 0.12.0, limite por lote, cota diária por organização, repetição idempotente e conflito de fingerprint aprovados em SQLite; migration Npgsql preparada |
| Graph live | não executado: ClientId/tenant/conta/destino controlado não foram fornecidos; Send real permaneceu desligado |
| Gmail mock | OAuth/PKCE/cofre, destino controlado, MIME UTF-8/PDF, draft-first, IDs, revogação, timeout único e reconciliação aprovados |
| Gmail live | não executado: Client ID desktop/projeto/conta/destino controlado não foram fornecidos; Send real permaneceu desligado |
| Relatórios | XLSX válido com 5 abas + 5 CSVs, resumo executivo, filtros/tabelas/freeze panes, datas tipadas, colunas técnicas recolhidas e formula injection neutralizada; QA 0.12.7 confirmou 5 abas/tabelas, 3 documentos sintéticos e zero erro de fórmula; PDF A4 de 2 páginas renderizado sem corte/sobreposição |
| PostgreSQL 18 real | migrations centrais, inclusive `AddPhase12ProductionRollout`, e teste de integração aprovados em banco descartável; banco removido e serviço encerrado |
| Concorrência crítica | atualização obsoleta de cliente e template rejeitada; API retorna 409 |
| Inativação | bloqueio `CLIENT_INACTIVE` e auditoria redigida aprovados |
| Isolamento/RBAC | tenant por claim, MFA e organização isolada aprovados |
| Import/export | export v1 e import dry-run de cliente/template aprovados |
| Publish `osx-arm64` e `win-x64` | aprovados; na 0.12.7 o cross-publish Windows gerou 341 arquivos e executável PE32+ GUI x86-64 com SHA-256 `6b4872d415cdf00dd56190908df9676a527536d3140ee29cf0bc33d30bde1c0e`; smoke físico Windows pendente |
| Pacote F11 `osx-arm64-beta validation` | 0.11.0 gerado em diretório temporário isolado; installer/portable/full/metadata e SHA-256 aprovados; marcador não distribuível presente |
| Pacote F12 `osx-arm64-beta validation` | 0.12.0 gerado em `/tmp` isolado; installer/portable/full/metadata, SHA-256, Mach-O arm64, versão e ICNS aprovados; marcador não distribuível presente |
| Pacote correção `osx-arm64-beta validation` | 0.12.3 gerado em `/tmp` isolado; installer/portable/full/metadata e todos os SHA-256 aprovados; bundle arm64/ícone/versão conferidos byte a byte e instalado em `/Applications/Folhas da Michelly.app` |
| Pacote correção focal `osx-arm64-beta validation` | 0.12.4 gerado em `/tmp` isolado; installer/portable/full/metadata e todos os SHA-256 aprovados; executável Mach-O arm64 conferido byte a byte e instalado em `/Applications/Folhas da Michelly.app` com a versão anterior preservada |
| Pacote correção `osx-arm64-beta validation` 0.12.6 | checksums aprovados; instalado em `/Applications/Folhas da Michelly.app`; hash do binário instalado idêntico ao pacote, versão `0.12.6.0` e assinatura ad-hoc; artefato não distribuível |
| Pacote manutenção `osx-arm64-beta validation` 0.12.7 | gerado em `artifacts/phase10/osx-arm64-beta/0.12.7`, `SHA256SUMS.txt` aprovado e versão `0.12.7.0` instalada; backup recuperável 0.12.6.0 preservado; assinatura ad-hoc falha no gate estrito, artefato não distribuível |
| Pacote F10 `osx-arm64-beta validation` | 0.10.0–0.10.2 gerados com Velopack 1.2.0; installer/portable/full/metadata e SHA-256 aprovados; marcador não distribuível presente |
| Pacotes F10 remotos `validation` | `osx-arm64` e `win-x64` gerados e publicados apenas como artefatos internos da execução manual; jobs assinados corretamente ignorados |
| Smoke pacote F10 macOS | bundles arm64 0.10.0/0.10.2 abriram; Configurações/update acessíveis e alinhados; versão dinâmica correta; feed ausente falhou fechado; encerrados sem OAuth/envio |
| Assinatura/notarização | bloqueada externamente: 0 identidades válidas no Keychain, Xcode completo/Apple Developer ID ausentes; certificado Windows ausente |
| Release stable | bloqueada por construção sem modo signed; CI protegida preparada, não executada/publicada |
| `dotnet list FolhasDaMichelly.slnx package --vulnerable --include-transitive` | aprovado; nenhum pacote vulnerável encontrado em nenhum projeto |

## CI

| Gate remoto | Resultado |
|---|---|
| [CI Fase 2 #32427855023](https://github.com/danziatus/folhas-da-michelly/actions/runs/32427855023) | aprovado no commit `508bdee` em macOS, Windows e Ubuntu/PostgreSQL |
| [CI Fase 3 #32436820673](https://github.com/danziatus/folhas-da-michelly/actions/runs/32436820673) | aprovada no commit `da2f785`: Ubuntu/PostgreSQL 1m18s, macOS 1m28s e Windows 4m14s |
| [CI Fase 4 #32438805357](https://github.com/danziatus/folhas-da-michelly/actions/runs/32438805357) | aprovada no commit `0f29ed9`: Ubuntu/PostgreSQL 1m16s, macOS 1m50s e Windows 5m24s |
| [CI Fase 5 #32440623032](https://github.com/danziatus/folhas-da-michelly/actions/runs/32440623032) | aprovada no commit `e5ec6d5`: Ubuntu/PostgreSQL 1m19s, macOS 1m33s e Windows 3m01s |
| [CI Fase 6 #32486895174](https://github.com/danziatus/folhas-da-michelly/actions/runs/32486895174) | aprovada no commit `563d92f`: Ubuntu/PostgreSQL 1m36s, macOS 1m57s e Windows 5m37s |
| [CI Fase 7 #32490742976](https://github.com/danziatus/folhas-da-michelly/actions/runs/32490742976) | aprovada no commit `172b0e4`: Ubuntu/PostgreSQL 1m48s, macOS 2m12s e Windows 7m17s |
| [CI da reformulação de UX #32495567388](https://github.com/danziatus/folhas-da-michelly/actions/runs/32495567388) | aprovada no commit `1d89069`: Ubuntu/PostgreSQL 1m29s, macOS ARM64 2m08s e Windows x64 4m09s |
| [CI Fase 8 #32509553502](https://github.com/danziatus/folhas-da-michelly/actions/runs/32509553502) | aprovada no commit `36b692c`: Ubuntu/PostgreSQL 1m36s, macOS ARM64 1m26s e Windows x64 9m45s |
| [CI da segunda revisão pré–Fase 9 #32516696990](https://github.com/danziatus/folhas-da-michelly/actions/runs/32516696990) | aprovada no commit `3a79067`: Ubuntu/PostgreSQL 1m19s, macOS ARM64 1m24s e Windows x64 4m04s |
| [CI Fase 9 #32521420396](https://github.com/danziatus/folhas-da-michelly/actions/runs/32521420396) | aprovada no commit `1c98b0f`: Ubuntu/PostgreSQL 1m32s, macOS ARM64 1m30s e Windows x64 4m21s |
| [CI Fase 10 #32529315320](https://github.com/danziatus/folhas-da-michelly/actions/runs/32529315320) | aprovada no commit `c7606ae`: Ubuntu/PostgreSQL 1m46s, macOS ARM64 2m18s e Windows x64 9m55s; build, testes e publish de ensaio verdes |
| [Packaging de validação Fase 10 #32529325316](https://github.com/danziatus/folhas-da-michelly/actions/runs/32529325316) | aprovado no commit `c7606ae`: entradas validadas, pacotes não distribuíveis macOS ARM64 (1m48s) e Windows x64 (5m50s) gerados; jobs assinados ignorados conforme o modo `validation` |
| [CI Fase 11 #32532892470](https://github.com/danziatus/folhas-da-michelly/actions/runs/32532892470) | aprovada no commit `8a33ad8`: Ubuntu/PostgreSQL 1m20s, macOS ARM64 2m13s e Windows x64 4m14s; formatação, build, 143 testes e publishes de ensaio verdes |
| [CI Fase 12 #32541965954](https://github.com/danziatus/folhas-da-michelly/actions/runs/32541965954) | aprovada no commit `d5d0720`: Ubuntu/PostgreSQL 1m21s, macOS ARM64 2m20s e Windows x64 4m22s; migration F12, formatação, build, 151 testes e publishes de ensaio verdes |
| [CI correção operacional/UX 0.12.3 #32662969685](https://github.com/danziatus/folhas-da-michelly/actions/runs/32662969685) | aprovada no commit `b6063f2`: Ubuntu/PostgreSQL 1m56s, macOS ARM64 2m37s e Windows x64 4m40s; restore bloqueado, formatação, build, 244 testes e publishes de ensaio verdes |
| [CI correção focal Clientes/Documentos 0.12.4 #32683126134](https://github.com/danziatus/folhas-da-michelly/actions/runs/32683126134) | aprovada no commit `3bf5953`: Ubuntu/PostgreSQL 1m46s, macOS ARM64 2m34s e Windows x64 4m50s; restore bloqueado, formatação, build, 248 testes e publishes de ensaio verdes |
| [CI correção de fluxo, histórico e relatórios 0.12.5 #32741094586](https://github.com/danziatus/folhas-da-michelly/actions/runs/32741094586) | aprovada no commit `30611b5`: Ubuntu/PostgreSQL 1m56s, macOS ARM64 2m23s e Windows x64 4m40s; restore bloqueado, formatação, build, 267 testes e publishes de ensaio verdes |
| [CI correção 0.12.6 #32793671723](https://github.com/danziatus/folhas-da-michelly/actions/runs/32793671723) | aprovada no commit `27121f7`: Ubuntu/PostgreSQL 1m32s, macOS ARM64 2m13s e Windows x64 4m58s; restore bloqueado, formatação, build, 296 testes, integração PostgreSQL e publishes dry-run verdes |

## Riscos e bloqueios conhecidos

1. login OIDC/PKCE interativo com TOTP ainda precisa de smoke real em macOS e Windows;
2. DPAPI CurrentUser passou em Windows hospedado no CI; acesso pelo app empacotado, UI OAuth e comportamento no perfil do operador ainda exigem estação Windows física;
3. caches locais contêm snapshots cadastrais e campos reconhecidos; apesar de ACL local, FileVault/BitLocker e política LGPD devem ser confirmados antes de dados reais;
4. o código aceita certificados OIDC de produção, mas provisionamento/rotação, TLS e restore de PostgreSQL/acervo em infraestrutura real permanecem pendentes;
5. assinatura/notarização e smoke visual em Windows 11 exigem máquinas/ferramentas externas;
6. PdfPig permanece pré-1.0; upgrade exige corpus dourado completo e revisão de licença;
7. PDFs somente-imagem exigem OCR, explicitamente adiado; arquivos criptografados/corrompidos são rejeitados sem tentativa de contorno;
8. smoke visual Windows 11 e comportamento real de drag-and-drop nos dois sistemas permanecem gates externos.
9. a revisão local agora persiste caminho e campos documentais; criptografia em repouso/ACL do dispositivo são obrigatórias antes de dados reais;
10. políticas semânticas e de agrupamento v1 precisam de homologação contábil com corpus anonimizado antes do piloto;
11. vencimento passado é `Warning` configurável, não conclusão sobre regularidade fiscal; o sistema não recalcula tributos ou folha.
12. destinatários, assunto e corpo agora existem no SQLite/recibo local; criptografia em repouso, ACL e retenção LGPD são gates antes de dados reais;
13. o HTML da Fase 6 é derivado de texto codificado; editor HTML sanitizado mais amplo não foi introduzido;
14. retry Graph foi implementado somente para operações idempotentes; limites reais do tenant precisam do gate dedicado;
15. a renderização visual XLSX é validada no macOS; Excel real no Windows continua parte do smoke externo.
16. rotas com `Recipient.DocumentTypeId` específico permanecem fail-closed até existir catálogo versionado que mapeie o tipo reconhecido ao UUID central; rotas globais e por estabelecimento estão operacionais.
17. consentimento/revogação Microsoft, consistência eventual de Sent Items e throttling real não foram exercitados sem conta Microsoft 365 dedicada.
18. o Keychain foi validado neste Mac, mas acesso após atualização/assinatura do bundle ainda precisa de smoke do artefato distribuído.
19. a inspeção visual automatizada passou no macOS por bundle `.app` temporário ad-hoc; o smoke visual em Windows 11 e em pacotes finais assinados continua pendente.
20. `gmail.compose` é escopo restrito: tela de consentimento, política/domínio e verificação Google são gates antes de distribuição externa.
21. OAuth, revogação, throttling e comportamento real de drafts Gmail ainda não foram exercitados sem projeto e conta Google dedicados.
22. o acervo local `AAAA/MM` organiza e deduplica; a Fase 9 analisa retenção sem excluir, mas prazo jurídico, backup externo e criptografia do volume continuam pendentes antes de dados reais.
23. se a resposta do Send Gmail se perde e o draft desaparece, o estado permanece intencionalmente ambíguo; ampliar escopo de leitura para inferir Sent não foi autorizado.
24. o pacote macOS validation não é assinado nem notarizado e jamais pode ser distribuído; o gate real exige Apple Developer ID e Xcode completo.
25. assinatura Authenticode, SmartScreen, instalação/update e reparação em Windows 11 real dependem de certificado e estação externa.
26. o endpoint HTTPS somente leitura dos quatro feeds não foi provisionado; o repositório privado não será acessado pelo Desktop com token embutido.
27. promoção beta → stable e rollback forward-only estão automatizados/documentados, mas só podem ser ensaiados integralmente depois dos certificados/feed.
28. staging HTTPS, organização de homologação, certificados, beta assinada e smoke físico Windows/macOS não foram fornecidos; portanto o aceite operacional F11 permanece bloqueado.
29. o checklist é uma evidência humana recuperável, não uma prova criptográfica da estação; confirmações precisam do roteiro e da responsabilidade operacional definidos em `docs/PILOT.md`.
30. métricas F11 são locais e agregadas; telemetria central/alertas gerenciados não foram adicionados para evitar PII e dependem de decisão futura.
31. a implementação F12 não substitui o aceite formal F11; staging HTTPS, beta assinada e estações físicas continuam ausentes, portanto `PilotApproved` permanece falso.
32. a cota F12 é central e conservadora: autorização consumida não é devolvida quando o provider falha; ajuste de limites exige análise operacional, nunca edição para contornar bloqueio.
33. produção gerenciada, stable assinada, feed HTTPS, restore de PostgreSQL/acervo, alertas e suporte não foram provisionados; todos os switches permanecem fechados.
34. o catálogo local padrão é autoridade de uma única instalação e não sincroniza automaticamente com o catálogo central; troca de perfil exige cópia protegida, prévia, confirmação e janela sem edição concorrente.
35. os botões Google/Microsoft continuam inativos sem configuração segura do provider; a correção não fornece credenciais, não conclui OAuth e não valida envio real.
36. reconhecimento de DOCX/XLSX e OCR continuam fora do motor atual; esses arquivos são recusados explicitamente e exigem fase própria de parser, corpus e validação contábil antes de serem aceitos.
37. o pacote macOS 0.12.6 foi instalado e verificado por hash/versão/assinatura, mas o smoke visual final permanece pendente porque o Mac está bloqueado; essa evidência não pode ser presumida a partir da instalação.

## Fora do escopo confirmado

Não foram implementados OCR, reconhecimento DOCX/XLSX, conta compartilhada, confirmação de entrega, sincronização automática entre catálogo local/central, publicação em feed ou deploy de produção. Graph e Gmail existem somente para gates controlados; nenhum Send real foi executado. Updater/packaging permanecem sem distribuição não assinada. A candidata 0.12.7 não inicia nova fase: a Fase 12 segue fail-closed e nenhum staging, produção, conta ou estação externa foi acionado.

## Próximo passo

Executar smoke físico em Windows e preparar os certificados de assinatura/notarização antes de distribuir os instaladores. Em checkpoint separado, escolher um único provider, registrar public client, conta dedicada e destinatário controlado; começar por Draft com `EmailSendEnabled=false` e só considerar Send após nova autorização explícita. Para abrir operacionalmente a Fase 12, primeiro aceitar a F11 em staging, concluir assinatura/smokes físicos, stable/feed, restore gerenciado, monitoramento, resposta a incidentes e suporte; então seguir `docs/PRODUCTION.md` com autorização humana específica. Não iniciar nova fase nem liberar Send automaticamente.
