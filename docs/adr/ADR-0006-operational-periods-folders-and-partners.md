# ADR-0006: Competência operacional, pastas locais e sócios separados

- Status: Accepted
- Data: 2026-08-21
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O volume mensal de documentos cresce continuamente. Sem um recorte visível de ano/mês, documentos, grupos, mensagens, histórico e relatórios de competências diferentes podem aparecer juntos. A entrada manual de arquivos também não deixa claro onde o PDF permanece. No cadastro PJ, CPF e nome de sócio precisam existir sem se tornar identificadores aptos a resolver documentos da empresa.

## Decisão

- O Desktop mantém uma competência operacional global com ano e mês, incluindo as opções “todos” e “sem mês definido”.
- Listas, contadores, aprovação em lote, preparação em lote, histórico e exportação de relatórios respeitam o recorte selecionado.
- A competência continua sendo um filtro de apresentação e operação; não substitui o `DocumentPeriod` tipado nem altera o conteúdo reconhecido.
- A pasta de entrada, a pasta do acervo, a pasta de relatórios, o período e a preferência de sessão de e-mail são preferências locais em SQLite.
- Ao importar, o Desktop copia o PDF para `acervo/AAAA/MM` com sufixo derivado do SHA-256. A validação existente por hash e chave semântica continua sendo a autoridade contra repetição.
- Caminhos e preferências não são sincronizados nem versionados; credenciais continuam exclusivamente no cofre nativo.
- Sócios e representantes formam uma coleção própria do agregado `Client`. Seu CPF é validado e normalizado, mas nunca entra em `client_identifiers`, no resolvedor de cliente ou na minimização enviada ao servidor de resolução.
- Pessoa física não possui sócios ou estabelecimentos. Empresa pode manter múltiplos sócios com papel explícito.

## Consequências

- O operador vê e processa uma competência por vez, reduzindo mistura acidental e lotes repetidos.
- O acervo ganha estrutura previsível, mas requer política futura de retenção, backup e proteção LGPD antes de dados reais.
- O mesmo conteúdo com outro nome ainda pode gerar uma cópia física distinta; a revisão por SHA-256 o bloqueia antes de aprovação e envio.
- Relatórios exportados ficam em uma subpasta do recorte selecionado.
- Há migrations para `client_partners` no PostgreSQL e `workspace_preferences` no SQLite.

## Evidência

- testes de domínio confirmam que sócio PJ é normalizado e não vira identificador; PF com sócio é rejeitada;
- teste SQLite confirma recuperação das três pastas, período e preferência de sessão após reinício;
- testes de UI confirmam CPF/CNPJ formatados, campos condicionais e editor de sócios;
- QA visual macOS percorreu Início, Clientes, Documentos, Envios, Relatórios e Configurações;
- 109 testes offline/default passaram sem envio real e a Fase 9 permaneceu não iniciada.

## Alternativas consideradas

- Tratar CPF de sócio como alias do cliente: rejeitado por risco de associar documento pessoal à empresa.
- Usar somente pastas escolhidas pelo operador sem acervo: rejeitado por ausência de organização previsível.
- Inferir automaticamente um mês único para toda a aplicação: rejeitado porque documentos podem representar intervalo, evento ou período sem mês.
