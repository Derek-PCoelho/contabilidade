# ADR-0010 — Catálogo local por padrão e modo conectado explícito

- Status: aceito como correção pós-Fase 12; operação conectada continua condicionada aos gates existentes
- Data: 2026-08-22
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O aplicativo instalado precisa permitir cadastrar, buscar e reconhecer clientes sem exigir que o operador também inicie e configure o Server. Antes desta correção, a composição do Desktop registrava o catálogo HTTP mesmo quando não havia endereço de API explicitamente configurado. A interface abria, mas salvar e buscar clientes dependia de um servidor local inexistente, o que tornava a jornada principal aparentemente inoperante.

O ADR-0002 continua correto para uma instalação compartilhada: quando há backend, PostgreSQL é a fonte de verdade e o Desktop acessa dados centrais somente pela API. Entretanto, o produto também precisa de um perfil autônomo, de uma única estação, no qual o catálogo seja realmente local. Os dois perfis não podem ser misturados silenciosamente, pois isso criaria risco de divergência cadastral, associação do documento ao cliente errado e falsa impressão de sincronização.

## Decisão

Adotar dois perfis de execução escolhidos no composition root do Desktop:

1. **Local, padrão:** na ausência de `Phase2:ApiBaseAddress`, `IClientCatalogService` usa `SqliteLocalClientCatalogService` e `IClientResolver` usa `SqliteLocalClientResolver`. Clientes, contatos, sócios/representantes, modelos e auditoria redigida são persistidos como `local-client`, `local-message-template` e `local-catalog-audit` no SQLite desta instalação.
2. **Conectado, somente por configuração explícita:** quando `Phase2:ApiBaseAddress` contém uma URI absoluta, catálogo e resolução usam os clientes HTTP. Nesse perfil permanecem válidas as fronteiras do ADR-0002: API ASP.NET Core como única porta e PostgreSQL como fonte de verdade compartilhada.
3. A seleção ocorre na inicialização. Alterar a configuração exige reiniciar o aplicativo; não existe fallback de escrita nem troca de autoridade durante uma operação. O perfil conectado pode usar seu último cache central de leitura conforme ADR-0002, mas os registros `client`/`message-template` desse cache são separados dos registros autoritativos `local-*`.
4. Não há sincronização ou merge automático entre os perfis. A cópia protegida/importação do catálogo pode transportar dados mediante prévia, validação e confirmação humanas, mas não representa promoção automática para o catálogo central.
5. As mesmas regras de domínio valem nos dois perfis: normalização e validação de CPF/CNPJ/e-mail, unicidade, concorrência otimista, inativação sem exclusão física, referências válidas de modelos e auditoria redigida.
6. O resolvedor local segue a ordem determinística e conservadora já definida: inscrição exata, raiz CNPJ coerente com nome, código, razão social/alias e fuzzy somente como sugestão. Ambiguidade e cliente inativo continuam bloqueando.
7. Esta decisão não habilita integração externa. `fake.local` permanece o provider padrão, o piloto fica desativado por padrão, produção permanece `Closed` e nenhum Graph, Gmail, OAuth ou `Send` real é acionado.
8. A mudança é uma correção funcional e de UX da versão 0.12.1, não uma nova fase do plano de execução.

## Consequências

- O aplicativo autônomo pode cadastrar, buscar, reabrir, inativar e resolver clientes após reinício sem depender do Server.
- O catálogo local é autoridade apenas naquela instalação; não deve ser interpretado como cadastro compartilhado ou backup do PostgreSQL.
- Trocar para o perfil conectado sem transferência explícita faz o operador enxergar o catálogo central, não uma fusão com o catálogo local.
- O arquivo SQLite passa a conter dados cadastrais operacionais e exige ACL do usuário, FileVault/BitLocker, retenção adequada e cópia protegida conforme `docs/OPERATIONS.md`.
- Indisponibilidade da API no perfil conectado nunca causa gravação oculta no catálogo local; eventual leitura do último cache central continua sujeita às regras do ADR-0002 e não muda a autoridade.
- Envio externo continua dependendo, cumulativamente, das políticas de provider, preflight central, piloto e produção. O perfil local não reduz esses controles.

## Relação com decisões anteriores

- Complementa o ADR-0002 com um perfil autônomo de uma estação; não altera a topologia nem a autoridade do perfil conectado.
- Preserva o ADR-0006 para competência, acervo e separação de sócios/identificadores.
- Preserva os ADRs 0008 e 0009: piloto e produção continuam gates técnicos explícitos e fechados por padrão.

## Alternativas consideradas

- **Sempre exigir Server local:** rejeitada porque transforma uma instalação desktop autônoma em uma implantação distribuída e deixa cadastro/busca indisponíveis sem configuração técnica.
- **Fazer fallback automático para SQLite quando a API falhar:** rejeitada porque duas autoridades poderiam aceitar alterações divergentes sem o conhecimento do operador.
- **Sincronizar automaticamente o catálogo local ao configurar a API:** adiada; exige política própria de identidade organizacional, conflito, homologação contábil, segurança e rollback.
