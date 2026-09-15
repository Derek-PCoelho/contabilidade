# Operações — Fase 12 com manutenção candidata pós-Fase 12 0.12.7

## Escolha do perfil operacional do Desktop

O Desktop escolhe a autoridade cadastral na inicialização. Não há botão de troca na interface nem fallback silencioso:

| Perfil | Configuração | Uso esperado |
|---|---|---|
| Local (padrão) | não definir `Phase2:ApiBaseAddress`/`Phase2__ApiBaseAddress` | uma estação autônoma; clientes, modelos, auditoria cadastral e resolução ficam no SQLite local |
| Conectado | definir `Phase2__ApiBaseAddress` com a URI absoluta HTTPS da API | instalação compartilhada; catálogo e resolução usam API/PostgreSQL conforme ADR-0002 |

Reinicie o aplicativo depois de mudar a configuração. Uma falha da API no perfil conectado pode apresentar o último cache central de leitura conforme ADR-0002, mas não faz o Desktop gravar no catálogo autoritativo local: os namespaces SQLite são separados. Também não existe merge automático ao passar do perfil local para o conectado: faça uma cópia `.fdmbackup`, confira a prévia de importação no destino e só aplique a transferência com autorização e backup. Evite edição concorrente nos dois perfis durante a migração.

No perfil local, o arquivo SQLite contém dados cadastrais operacionais. Restrinja-o ao usuário da estação, mantenha FileVault/BitLocker e não o envie ao Git ou suporte. O perfil local permite cadastro, busca, inativação, modelos e resolução de documentos sem iniciar o Server, mas não habilita login externo, sincronização compartilhada nem e-mail real.

Abra somente a instância já exibida do Desktop. A candidata 0.12.7 preserva o bloqueio local por conta de usuário: uma segunda abertura termina antes de acessar o SQLite, evitando que duas janelas sobrescrevam cadastro, revisão ou aprovação. A versão `0.12.7.0` está instalada em `/Applications/Folhas da Michelly.app`; a instalação anterior `0.12.6.0` está preservada em `artifacts/install-backups/Folhas da Michelly 0.12.6 pre-0.12.7.app`. Se o ícone parecer não responder, procure primeiro uma janela já aberta no Dock/barra de tarefas.

O pacote instalado é `validation`: `SHA256SUMS.txt` foi aprovado, mas a assinatura é somente ad-hoc e falha no gate estrito como esperado. Ele não é uma versão distribuível nem substitui assinatura Developer ID, notarização ou pacote beta assinado. O cross-publish `win-x64` contém 341 arquivos e executável PE32+ GUI x86-64 com SHA-256 `6b4872d415cdf00dd56190908df9676a527536d3140ee29cf0bc33d30bde1c0e`; não equivale a pacote assinado nem a smoke em Windows físico.

## PostgreSQL de desenvolvimento

Configure uma instância PostgreSQL 18 e uma connection string por variável/secret de ambiente. Não versione senha. Para aplicar migrations:

```bash
dotnet tool restore
dotnet ef database update --project src/FolhasDaMichelly.Infrastructure --context FolhasDbContext --connection "$FOLHAS_POSTGRES"
```

O servidor só migra/semeia papéis e o public client OIDC quando `Database:Initialize=true`. Em produção, mantenha inicialização controlada no pipeline e configure certificados OIDC; o processo recusa fallback efêmero.

## Saúde

- `/health/live`: processo ativo, sem dependências;
- `/health/ready`: conectividade com o banco central;
- `/`: status não sensível da fase.

## Catálogo

- no perfil local, salvar/buscar/abrir/inativar, modelos, auditoria e resolução usam `catalog_cache` no SQLite;
- **Cadastrar cliente** cria um registro; depois de abrir um cliente, a ação muda para **Atualizar cadastro** e preserva sua identidade/versão;
- inativar ou reativar é uma alteração persistida imediatamente. Ative **Mostrar inativos** para localizar, revisar ou reativar um registro sem recriá-lo;
- ao informar CPF/CNPJ já pertencente a um cadastro inativo, abra o registro recuperado e decida entre corrigir ou reativar; não tente criar outra identidade fiscal;
- CPF/CNPJ aceitam digitação com ou sem pontuação, mas os dígitos verificadores continuam obrigatórios. Um número aleatório matematicamente inválido deve ser recusado. Não há consulta à Receita Federal nem a outra base externa;
- no sócio/representante, o CPF continua opcional. Se preenchido com e-mail, quantidade de dígitos incorreta ou dígito verificador inválido, leia a orientação exibida junto ao campo e corrija antes de adicionar. O CPF do representante nunca vira identificador da empresa para resolver documentos;
- clique diretamente na linha para abrir o cliente; a busca também é executada com Enter. **Ocultar CPF/CNPJ na lista** é uma preferência visual, não uma alteração do cadastro;
- use **Recolher lista** quando precisar de todo o espaço para o formulário e **Recolher cadastro** quando quiser priorizar pesquisa e seleção. **Novo cliente** e a abertura de uma linha focam o formulário automaticamente; **Mostrar lista** a restaura sem apagar o que está sendo editado;
- salvar/inativar/reativar confirma o cadastro e a revalidação documental na mesma transação local; se a revisão falhar, nada fica parcialmente confirmado. E-mail legado ativo incompleto não impede reabrir/reativar, mas bloqueia o uso até correção; contato inválido já inativo não bloqueia prontidão nem uma edição não relacionada;
- **Excluir cadastro da lista** é um arquivamento lógico, permitido somente após inativação e quando não houver documentos/modelos que dependam do cliente; a auditoria é preservada;
- o corpo do modelo admite até 20.000 caracteres. Insira variáveis pelos botões com nomes legíveis; chave digitada manualmente que não pertença ao catálogo suportado deve ser corrigida antes de salvar;
- use as predefinições gerais de empresa ou pessoa física quando não quiser redigir do zero. Um modelo pode ser inativado ou excluído da lista com confirmação e preservação da auditoria;
- no perfil conectado, exportação usa `GET /api/clients/catalog/export` e exige leitura de clientes e templates;
- no perfil conectado, validação de importação usa `POST /api/clients/catalog/import` com `DryRun=true`; a aplicação repete com `DryRun=false` e `OverwriteExisting` conscientemente definido;
- conflitos de versão exigem recarga do registro; no perfil conectado aparecem como HTTP 409 e nunca devem ser contornados sobrescrevendo a versão atual;
- inativação preserva histórico e retorna `CLIENT_INACTIVE` no endpoint de elegibilidade.

## Revisão documental local

- `LocalCacheDbContext` aplica `AddPhase5DocumentReview` na inicialização do Desktop;
- no perfil conectado, o workspace é particionado pela organização do token; no perfil local, usa o escopo isolado da instalação e resolve somente contra seu próprio catálogo SQLite;
- abrir o app relê arquivos existentes e revoga snapshots incompatíveis;
- mover, excluir ou alterar um PDF bloqueia o item até nova importação/revisão;
- não edite `cache.db` manualmente: documentos/grupos são transacionais e a auditoria é append-only;
- antes de limpar o SQLite em suporte, gere `.fdmbackup` do catálogo e copie acervo/relatórios separadamente; a cópia não contém workspace de revisão, PDFs ou incidentes;
- o workflow local usa Fake por padrão; Graph/Gmail só abrem rede quando explicitamente selecionados/configurados.

Na importação 0.12.1, o PDF é reconhecido antes de ser copiado para o acervo; a competência reconhecida determina `AAAA/MM`. Conteúdo já visto é ignorado por SHA-256. A linha só aparece na interface depois da persistência; se essa etapa falhar ou for cancelada, uma cópia recém-criada é removida. Quando um lote contém mais de uma competência, a interface mostra todos os períodos e mantém um documento importado selecionado; o aviso **Mostrar todos** recupera itens escondidos por um filtro anterior.

Na revisão 0.12.2, selecione o documento e leia **O que precisa ser conferido** antes de tentar aprovar. A tela transforma os bloqueios conhecidos em próximas ações:

- cliente inativo: abra o cadastro diretamente, reative/corrija e volte para revalidar;
- mais de uma associação elegível: escolha somente uma alternativa oferecida pelo resolvedor e justifique;
- competência incorreta: informe mês/ano corrigidos e uma justificativa. O `PeriodOverride` fica persistido e substitui o período extraído apenas naquele documento;
- correção de competência indevida: use **Voltar à competência reconhecida** para remover o override e recalcular o documento;
- arquivo colocado no lote errado: use **Retirar da revisão**, confira o aviso e confirme a segunda etapa.

Corrigir/restaurar competência, mudar cliente ou retirar documento revoga qualquer aprovação afetada, executa novamente validações e duplicidades e refaz o agrupamento. A retirada é lógica: o item sai do workspace ativo e gera evento de auditoria, mas o PDF físico permanece no acervo para recuperação manual. Se a retirada ou revalidação deixar um grupo sem documentos, esse grupo é removido automaticamente; a abertura do aplicativo também limpa grupos vazios herdados de versões anteriores.

Na apresentação 0.12.4, siga os três blocos **Adicionar → Conferir → Liberar**. Existe uma única rolagem principal; escolha o documento e leia a situação, o cliente identificado e a próxima ação. **Liberar** não envia e-mail: apenas permite que o documento siga para preparar a mensagem. **Retirar deste período** é a ação apropriada para repetir um teste, sem apagar o arquivo do acervo. O reconhecedor atual aceita somente PDF; numa pasta mista, PDFs seguem e DOCX, XLSX e outros formatos permanecem na origem, com quantidade/extensões recusadas no resumo. Não renomeie a extensão para contornar a validação MIME.

Na candidata 0.12.6, um **conjunto para mensagem** continua reunindo somente documentos do mesmo cliente, estabelecimento, competência e código/versão da política que podem seguir juntos em uma única mensagem. Agosto, setembro e dezembro do mesmo cliente são, portanto, três conjuntos e originam três mensagens independentes. Abra **Ver o conjunto completo** — o rótulo também informa a quantidade de documentos — para conferir arquivos e tipos juntos sem perder o documento individual selecionado. Depois da conferência, escolha conscientemente um dos caminhos:

- **Liberar este conjunto** aprova somente os documentos reunidos no cartão selecionado;
- **Liberar este cliente** aprova todos os conjuntos prontos daquele CPF/CNPJ, mesmo em competências diferentes, sem unir os conjuntos nem as futuras mensagens;
- **Liberar tudo pronto** aprova os conjuntos elegíveis de todos os clientes de um único mês e ano.

A liberação por cliente é atômica e exige a seleção exata de todos os conjuntos prontos desse cliente. Se faltar um ID, houver outro cliente ou qualquer conjunto solicitado estiver ou ficar bloqueado, com erro, duplicado ou invalidado por mudança concorrente, nada é aprovado. Outros conjuntos bloqueados do cliente não são solicitados e permanecem fora da liberação. Não repita a ação tentando contornar o bloqueio: abra o conjunto indicado, corrija a pendência e revalide. A liberação geral continua indisponível em “Todos os períodos”, pois atravessar clientes e competências numa única ação ampliaria o risco de mistura. A liberação registra a aprovação documental, mas não prepara nem envia e-mail.

O cliente mostrado na linha vem somente da resolução determinística: CNPJ/CPF elegível, raiz CNPJ coerente ou escolha humana registrada. CPF de sócio/empregado não associa documento à empresa. Quando não houver correspondência inequívoca, a tela declara **Cliente ainda não identificado**, abre a correção e não cria grupo por suposição. Depois da identificação, o resumo explica a organização por cliente, estabelecimento e competência.

**Organização manual** inicia recolhida porque é uma exceção. Use **Separar o documento selecionado** somente quando aquele arquivo deva formar outra mensagem; informe o motivo. Use **Unir conjuntos compatíveis** somente quando a lista oferecer outro conjunto com o mesmo cliente, estabelecimento, competência e código/versão da política; o rótulo informa período, quantidade e tipos para não repetir nomes indistinguíveis. A união não serve para misturar períodos, clientes, estabelecimentos ou políticas incompatíveis. Separar/unir exige motivo, registra auditoria, invalida aprovação anterior e obriga nova conferência. Evidências de identificação também permanecem recolhidas e não competem com o fluxo comum.

Para aprovar tudo, preparar ou concluir em lote, selecione um único mês e ano. A única exceção explícita é **Liberar este cliente**, que pode aprovar os conjuntos prontos do mesmo cliente em competências distintas, mas continua atômica e não os funde. A aprovação mensal valida todos os grupos antes da primeira alteração e persiste o conjunto uma única vez: qualquer item inválido cancela o lote inteiro. A tela bloqueia o lote geral em “Todos os períodos” e também impede executar lote antigo que contenha competência oculta. Mudança, renomeação ou inativação de cliente exige revalidação; uma correspondência que passe a indicar outro cliente fica bloqueada até confirmação humana e invalida a aprovação anterior.

## Execução local, perfil conectado e contas de e-mail

O perfil local não precisa de Server, PostgreSQL nem login para cadastrar clientes, reconhecer/revisar PDFs, aprovar grupos, preparar rascunhos e gerar relatórios locais. O `LocalDesktopOperationContextAccessor` concede exatamente `documents.process`, `batch.approve`, `email.draft` e `audit.export`. Ele nunca concede `email.send`; o provider padrão continua `fake.local`, portanto essas permissões não criam conexão de rede nem liberam mensagem real.

No perfil conectado, `JwtDocumentReviewContextAccessor` lê do token a organização, o usuário e suas permissões. Sem token válido, o Desktop usa o escopo isolado `unauthenticated-connected` e nenhuma permissão de despacho. Não copie um token do console nem o digite em configuração: ele é uma prova temporária de login emitida pelo Server e guardada no Keychain do macOS ou DPAPI do Windows.

Os componentes têm funções distintas:

| Componente | Quando é necessário | O que faz |
|---|---|---|
| Desktop local | uso em uma única estação | mantém catálogo/revisão no SQLite e acervo no disco; funciona sem Server |
| Server ASP.NET Core | login, políticas e dados compartilhados entre estações | autentica, autoriza e expõe a API; o Desktop fala com ele por HTTPS |
| PostgreSQL | somente com Server/perfil conectado | guarda a autoridade central de usuários, organizações e catálogo; nunca é acessado diretamente pelo Desktop |
| Token | depois do login no perfil conectado | identifica sessão, organização e permissões por prazo limitado; não é senha nem configuração permanente |

Os adaptadores Gmail e Microsoft 365 existem, mas um instalador não pode inventar a identidade oficial do aplicativo. Antes de habilitar **Conectar**, é necessário registrar o aplicativo em um projeto Google Cloud ou Microsoft Entra, obter o Client ID público, configurar consentimento/redirect e definir uma conta e um destino controlados. Client secret, senha de e-mail e token nunca entram no aplicativo, na documentação ou no Git. Até essa configuração existir, os botões permanecem desabilitados intencionalmente; o cadastro/revisão local continua utilizável.

Em **Envios**, percorra a sequência vertical **1. Preparar → 2. Conferir destinatário, texto e anexos → 3. Aprovar → 4. Concluir**. Escolha o modo antes de preparar. Depois que a mensagem é composta, Teste/Rascunho/Send fica imutável para proteger destinatário, anexos e fingerprint; para mudar o modo, volte ao primeiro passo e prepare uma nova composição.

A lista principal é a **fila atual**. Ela mostra apenas a versão corrente de cada conjunto, com referência curta, cliente, competência e anexos. Use a busca para localizar esses campos e os filtros **Pendentes**, **Concluídas** ou **Todas** para controlar volume; versões substituídas/canceladas continuam preservadas no Histórico, não como duplicatas acionáveis. O mesmo cliente pode aparecer mais de uma vez quando possui conjuntos ou competências diferentes: confira a referência, o período e os anexos. Depois de preparar, aprovar ou concluir, o aplicativo deve manter essa mensagem selecionada e permanecer na etapa correspondente. Ao voltar de Envios para Documentos, o conjunto é realinhado ao documento corrente; se a seleção não persistir, não execute outra vez até atualizar a fila e confirmar o estado.

Tipos documentais e termos visíveis são apresentados em português. No provider local, **Teste seguro** é somente uma simulação dentro do aplicativo: nenhum e-mail sai da máquina e nenhuma chegada é comprovada.

Em **Relatórios**, faça duas escolhas independentes: **Cliente** — todos ou um selecionado — e **Período** — todos, mês, ano ou intervalo de competências mensais. O pacote usa a interseção das escolhas; por exemplo, “Empresa Alfa, de 08/2026 a 12/2026”. Confira a cobertura impressa no XLSX/PDF antes de compartilhar. O XLSX oferece a análise detalhada, o PDF apresenta a leitura organizada e relaciona tipos e nomes de todos os documentos do recorte, inclusive os que ainda não possuem mensagem, e os CSVs preservam as visões tabulares para conferência. O resultado inclui documentos ainda sem mensagem e pendências documentais, além das comunicações correntes já preparadas ou concluídas; tentativa antiga de uma versão substituída permanece no Histórico, mas não entra no relatório corrente. **Recebido pelo serviço de e-mail** significa aceitação técnica pelo provider; não significa entrega confirmada na caixa do cliente. Simulação local, ausência de tentativa e entrega não comprovada devem permanecer distintas.

Na 0.12.7, a exportação também consulta os snapshots imutáveis dos anexos das mensagens. Assim, um documento que não esteja mais na revisão corrente não desaparece do relatório da comunicação que o registrou; a deduplicação por `DocumentId` evita contar duas vezes o mesmo item. Não use a expectativa verbal de quantidade para completar dados: a base local observada da BOREAL contém somente dois PDFs persistidos. O cenário automatizado de três anexos é sintético e verifica precisamente um terceiro item existente apenas no snapshot.

Em **Histórico**, combine a competência global com recorte de dia, faixa de horas, intervalo, mês, ano, cliente, documento e texto. As seções de documentos conferidos/aprovados e comunicações são recolhíveis; recolhê-las não altera a consulta. Selecione o cliente no próprio Histórico para alternar a auditoria cadastral, sem precisar abrir ou editar o cadastro. As linhas resumem a ação com cliente, competência, tipos e quantidade de documentos quando o evento contiver esse contexto; uma retirada conserva a identidade e os metadados do documento original em vez de herdar outro item que hoje permaneça no grupo.

**Limpar tudo que aparece agora** registra somente os IDs exibidos no recorte corrente. Confirme quantidade e filtros antes de aplicar: eventos fora da tela não entram implicitamente na regra. A operação é exclusivamente visual e recuperável; use a restauração para tornar os registros visíveis novamente. Nenhum evento de auditoria é apagado ou reescrito, e a trilha original permanece append-only. Não limpe `cache.db` para obter o mesmo efeito.

## Microsoft Graph controlado

Ordem operacional recomendada:

1. registre um public client de teste no Microsoft Entra com redirect `http://localhost`;
2. configure `ProviderKey`, ClientId, tenant e um único destinatário controlado por variável local;
3. mantenha `EmailSendEnabled=false`, abra o Desktop e conecte a conta de teste;
4. valide Draft e remoção pela suíte opt-in;
5. para qualquer saída Graph (`Test`/`Send`), habilite `Phase7__MicrosoftGraph__EmailSendEnabled=true` no Desktop e no Server e reconecte para consentir `Mail.Send`; defina também `FOLHAS_GRAPH_LIVE_SEND=true` somente no gate Send protegido;
6. confirme visualmente destino efetivo, assunto, anexos e fingerprint antes da aprovação/execução;
7. desligue novamente o kill switch ao finalizar.

Nunca copie cache MSAL, exporte token, forneça client secret ou use conta/destinatário de produção. `AcceptedByProvider` significa somente que o Graph aceitou o trabalho.

Em revogação, desconecte no Desktop, revogue o consentimento/sessões no tenant e reconecte. Em `Ambiguous`, use apenas `Reconciliar sem reenviar`; se continuar ausente, preserve o incidente e não crie nova composição.

Se o preflight retornar `SEND_DISABLED_REMOTELY`, `APP_VERSION_BELOW_MINIMUM`, `REMOTE_SEND_FORBIDDEN`, `REMOTE_SEND_RESPONSE_INVALID` ou `REMOTE_SEND_GUARD_UNAVAILABLE`, não contorne a API. Verifique configuração/saúde do Server, banco central, MFA e sessão do dispositivo; repita somente o preflight após corrigir a causa.

O teste live sem Send remove o draft sintético no `finally`. Se a estação ou rede cair antes da limpeza, localize o assunto `[FASE 7 — TESTE CONTROLADO]` na conta dedicada e remova o rascunho manualmente após registrar o incidente.

## Gmail controlado

Ordem operacional recomendada:

1. crie projeto Google dedicado, habilite Gmail API e configure tela de consentimento/usuário de teste;
2. crie Client ID do tipo aplicativo Desktop; não crie/forneça client secret;
3. configure `Phase8__ProviderKey=google.gmail`, ClientId e único destinatário controlado; mantenha os switches Desktop/Server desligados;
4. conecte a conta Google dedicada pelo Desktop e confira conta/escopo `gmail.compose`;
5. use `FOLHAS_GMAIL_LIVE_TEST=true` para o gate de draft sintético; ele remove o draft no `finally`;
6. somente após conferência explícita, habilite `Phase8__Gmail__EmailSendEnabled=true` no Desktop e no Server e acrescente `FOLHAS_GMAIL_LIVE_SEND=true` para um Send sintético ao destino controlado;
7. revogue/desconecte e desligue novamente os switches ao concluir.

Em `AUTH_REVOKED`, reconecte pela UI; não copie/exporte tokens. Em `Ambiguous`, use somente reconciliação. Se o draft não existir após timeout, não conclua que foi enviado e não repita: registre o incidente e confira manualmente a conta dedicada. Antes de distribuição externa, complete política/domínio e verificação Google para o escopo restrito.

## Incidentes

- banco/auditoria central indisponível: preserve fila/cache e mantenha os kill switches Graph/Gmail desligados;
- conflito: interromper retry daquela operação e exigir resolução futura;
- dispositivo perdido: revogar `device_session`; tokens subsequentes e políticas de API falham;
- suspeita de token: revogar autorização/token no servidor e dispositivo associado;
- na interface, vincular a ocorrência à tentativa, conter, apurar, resolver e encerrar com justificativa; a limpeza visual recuperável não apaga nem edita o histórico de auditoria.

## Cópia protegida e restauração

1. Em **Configurações > Cópia de segurança**, informe uma senha exclusiva com pelo menos 12 caracteres.
2. Salve o arquivo `.fdmbackup` em mídia/volume criptografado e guarde a senha separadamente.
3. Para testar restore, escolha a cópia, informe a senha e confira a prévia. A aplicação só ocorre após **Confirmar restauração**.
4. Registre data, responsável, quantidade prevista e resultado. Faça o ensaio com catálogo sintético ou ambiente isolado.

A cópia contém somente clientes, contatos e modelos de mensagem da autoridade cadastral ativa. Não contém PDFs, workspace de revisão, incidentes, histórico de envios, cache de reconhecimento, tokens ou autorizações. Copie o acervo separadamente preservando `AAAA/MM` e valide amostras/hashes. Tokens nunca são restaurados: reconecte as contas.

No perfil local, o `.fdmbackup` é o meio suportado para transportar o catálogo entre instalações; copiar `cache.db` manualmente não é procedimento de migração. No perfil conectado, a cópia protege o catálogo acessível pela API, mas não substitui `pg_dump`, backup gerenciado nem restore testado do PostgreSQL.

Para PostgreSQL, use backup consistente do provedor/`pg_dump` e restaure em banco isolado. Valide migrations, contagens por organização, usuários/dispositivos e `/health/ready`. Nunca teste restauração sobre produção.

## Certificados e hosts de produção

Produção exige `AllowedHosts` explícito e `Authentication__Oidc__SigningCertificatePath`, `Authentication__Oidc__SigningCertificatePassword`, `Authentication__Oidc__EncryptionCertificatePath` e `Authentication__Oidc__EncryptionCertificatePassword`. Use dois certificados RSA separados do TLS, com chave privada, permissões restritas e mais de 30 dias de validade. Ensaie rotação; nunca versione PFX ou senha.

Observabilidade central, alertas e drill completo em infraestrutura gerenciada ainda precisam ser concluídos antes do aceite operacional do piloto da Fase 11.

## Atualizações, canais e reparação

- o Desktop consulta somente feed HTTPS sem credenciais na URL; feed local exige configuração explícita de validação;
- a interface comum apresenta o canal Estável; Beta e detalhes de release ficam restritos à configuração/suporte supervisionado;
- nenhuma atualização é aplicada automaticamente: verificar, baixar e reiniciar são decisões separadas;
- `Phase10__EmailSendEnabled=false` bloqueia Graph e Gmail globalmente, independentemente dos switches de cada provider;
- `Phase10__MinimumSupportedVersion` é aplicada junto à versão mínima do provider e deve ser elevada somente quando uma atualização assinada estiver disponível;
- nunca publique os artefatos com `UNSIGNED-VALIDATION-ONLY.txt`;
- em regressão, desligue Send/canal, gere reparação com SemVer maior a partir do último commit bom e preserve os artefatos incidentados.

Use o workflow manual protegido e o runbook completo de assinatura, promoção, integridade e rollback em `docs/RELEASES.md`. Sem certificados, Xcode completo e feed HTTPS, a operação correta é manter o canal indisponível e aguardar — nunca remover os bloqueios.

## Piloto supervisionado

O ponto de partida é `docs/PILOT.md`. Configure o servidor com `ASPNETCORE_ENVIRONMENT=Staging`, host/TLS explícitos e os valores não secretos de `deploy/staging/.env.example`; secrets e connection strings reais vêm exclusivamente do cofre do ambiente.

Antes de abrir a estação:

1. confirme health e política com `bash tools/pilot/verify-staging.sh`;
2. verifique na resposta que Test/Draft estão ativos, Send está falso, máximo é cinco e mínimo é 0.12.0;
3. instale somente beta assinada, nunca pacote validation;
4. importe apenas corpus sintético/anonimizado conferido por duas pessoas;
5. conclua o painel **Configurações > Piloto supervisionado** em macOS e Windows;
6. mantenha tentativa Send em zero e trate qualquer falha/ambiguidade antes de continuar.

O painel registra mudança de checklist por organização e mostra contagens agregadas. “Pronto para supervisão” não autoriza produção, e-mail real ou Fase 12.

Na instalação comum 0.12.3, `Phase11:Enabled` é falso quando não foi configurado explicitamente. Produção gradual e piloto supervisionado são controles técnicos de homologação; permanecem ocultos da jornada cotidiana quando fechados e aparecem apenas nos detalhes de suporte quando aplicáveis.

### Parada e rollback do piloto

Em tentativa Send, mistura de cliente, dado real inesperado, PII/token em log, duplicidade encaminhada, ambiguidade ou ocorrência alta/crítica: pare o lote, feche os switches F7/F8/F10/F11, revogue sessões afetadas, preserve evidência redigida e abra ocorrência. Restaure banco/acervo em infraestrutura isolada e publique reparação assinada forward-only com SemVer maior. Não faça downgrade manual nem sobrescreva o feed.

Os smokes físicos, staging, certificados e contas externas ausentes devem permanecer registrados como bloqueios; nunca marcar automaticamente um item que não foi observado.

## Produção gradual

A F12 não transforma o checklist local em autoridade de produção. Use o runbook completo em `docs/PRODUCTION.md`; `deploy/production/.env.example` é somente um inventário seguro de configuração, com switches fechados e placeholders.

O endpoint `GET /api/production/policy` exige sessão com `email.send` e MFA. Ele retorna prontidão, papel da sessão, limite por lote, autorizações agregadas do dia e bloqueadores. Antes de qualquer abertura, confirme health/política com `bash tools/production/verify-readiness.sh`; o script é read-only e não substitui a revisão humana de destinatário/anexos/aprovação.

Cada autorização de `Send` consome a cota diária conservadoramente antes do provider. Repetir a mesma operação e fingerprint é idempotente; `PRODUCTION_IDEMPOTENCY_CONFLICT` exige interromper e revisar, nunca alterar a requisição para contornar a trava. `PRODUCTION_DAILY_LIMIT_REACHED`, `PRODUCTION_BATCH_LIMIT_EXCEEDED`, `PRODUCTION_ROLE_FORBIDDEN` e `PRODUCTION_ROLLOUT_CLOSED` também são bloqueios operacionais, não erros para retry.

Na parada, feche F12 primeiro, depois F10 e o provider, preserve evidência append-only e trate a tentativa conforme o estado. Nunca repita operação ambígua. A reabertura retorna a `Limited` com nova autorização humana; não há promoção automática a `Gradual`.

## Limite desta correção

A candidata 0.12.7 amplia a manutenção pós-Fase 12 com validação acionável do representante, `{{escritorio.nome}}` definido como **AL Contadores Associados**, cartão de Competência alinhado, inventário/layout de Relatórios e Histórico filtrável/fiel/recuperável, sem abrir uma nova fase e sem novo ADR. Restore bloqueado, formatação, build e **327 testes** — 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI — passaram; a auditoria NuGet encontrou zero vulnerabilidade. XLSX de 5 abas/tabelas com 3 documentos sintéticos e PDF A4 de 2 páginas passaram no QA, e Início/Relatórios/Histórico foram percorridos em 1170×768 e 960×640. Pacote/instalação, CI tripla `32873335418` e pacotes internos `validation` `32873883491` foram verificados; assinatura/notarização e smoke físico Windows permanecem checkpoints. Os botões Google e Microsoft apenas refletem providers já implementados: ficam desabilitados até existir registro/configuração segura e não solicitam senha. `fake.local`, piloto desativado e produção `Closed` continuam os defaults. Nenhum OAuth, provider externo, publicação stable, deploy, Draft live ou `Send` real foi autorizado ou executado por esta correção.
