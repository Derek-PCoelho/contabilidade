# Segurança — baseline da Fase 12

## Controles implementados

- OIDC Authorization Code + PKCE, public client e navegador do sistema;
- Identity com senha forte, lockout, e-mail confirmado e TOTP para papéis privilegiados;
- access token curto, refresh token revogável e sessão vinculada a dispositivo;
- RBAC server-side e MFA obrigatório para escrita/gestão;
- tenant derivado exclusivamente dos claims autenticados;
- limites particionados por usuário/origem para autenticação, leitura, escrita e operações sensíveis, além de limite global;
- cookies `HttpOnly`, `Secure`, `SameSite=Lax`, HSTS/HTTPS fora de Development/Testing;
- produção falha fechada se hosts explícitos e certificados OIDC distintos de assinatura/criptografia não estiverem configurados; certificados inválidos ou a menos de 30 dias do vencimento são recusados;
- SQLite sem tokens e logs/audit com dados redigidos mínimos;
- modo de autenticação sintética limitado a Development/Testing, desativado por padrão.
- CPF/CNPJ validados e normalizados no domínio, mas mascarados nas listagens;
- e-mails normalizados com papel de entrega explícito, sem inferência de cópia;
- auditoria cadastral contém somente versão, estado e contagens, nunca documento ou e-mail;
- import/export e templates respeitam tenant e políticas próprias; escritas exigem MFA.
- extensão e assinatura `%PDF-` validadas antes do parser; limites de 25 MB, 100 páginas, 2 milhões de caracteres e timeout cooperativo;
- SHA-256 calculado em streaming; cache é versionado e não guarda bytes do PDF nem caminho absoluto;
- PDF e texto integral permanecem locais; o endpoint recebe somente identificadores/nome elegíveis, com snippet de evidência mascarado;
- CPF de empregado/sócio, CNPJ de sindicato/emissor e valores financeiros são removidos da requisição de resolução;
- organização deriva exclusivamente da sessão autenticada; o contrato de resolução não contém `OrganizationId`;
- arquivo corrompido/protegido é rejeitado; texto vazio vira `NeedsOcr`, sem OCR implícito;
- fuzzy nunca resolve automaticamente e raiz CNPJ ambígua bloqueia.
- revisão SQLite é particionada pela claim `organization_id`; o operador não fornece tenant em comandos;
- override aceita apenas alternativa autenticada do resolvedor e exige justificativa auditável;
- SHA-256 é relido antes da aprovação; alteração/ausência do arquivo bloqueia e revoga snapshot;
- duplicidade exata ou semântica fica sem grupo e fora de aprovação individual/em lote;
- `Error` e `Blocker` impedem aprovação por construção; split/merge incompatível falha fechado;
- snapshot aprovado contém somente metadados/hashes necessários e nunca habilita envio;
- parser do 13º deduplica cabeçalho repetido, preserva CNPJs distintos e bloqueia raízes diferentes.
- composição usa whitelist de placeholders e nunca executa código; placeholder desconhecido é `Blocker`;
- HTML é derivado do texto por encoding, sem script ou conteúdo ativo configurável;
- Test exige destino `example.invalid`, prefixa o assunto e preserva a rota original apenas para conferência;
- aprovação do despacho contém fingerprint, revisão de template, destinatários e hashes; mudança invalida o snapshot;
- `DeliveryAttempt(Pending)` é persistida antes da chamada; `Pending/Ambiguous` proíbem retry cego;
- provider desconhecido falha fechado; `fake.local` permanece padrão e continua sem `HttpClient`/socket;
- Send simulado exige `email.send`, kill switch, versão mínima e confirmação `CONFIRMAR N`;
- XLSX/CSV neutralizam valores iniciados por `=`, `+`, `-` ou `@` para impedir formula injection;
- relatórios e auditoria não afirmam entrega: `AcceptedByProvider` permanece distinto de `Delivered`.
- login do app e OAuth do e-mail usam sessões, chaves de cofre e eventos de auditoria separados;
- runtime persiste segredos somente no Keychain do macOS ou em arquivo DPAPI CurrentUser no Windows; testes usam memória;
- MSAL é public client com navegador do sistema, sem client secret/senha Microsoft;
- Graph usa `Mail.ReadWrite` para drafts/reconciliação e só pede `Mail.Send` quando o kill switch real está ativo;
- Graph substitui qualquer rota por um único destino controlado, zera Cc, prefixa o assunto e revalida o endereço antes do HTTP;
- toda saída Graph (`Test`/`Send`) exige `email.send`, aprovação, versão, kill switches local/remoto e preflight central com MFA, sessão ativa e auditoria redigida; indisponibilidade ou resposta incoerente bloqueia antes do Graph;
- Send Graph exige adicionalmente `CONFIRMAR GRAPH N`/`CONFIRMAR GRAPH LOTE G N`; Test não usa a rota do cliente e continua limitado à caixa controlada;
- anexos têm existência, tamanho e SHA-256 relidos antes de sair da máquina;
- envio Graph é draft-first, usa ID imutável e propriedade de idempotência; HTTP 202 vira somente `AcceptedByProvider`;
- timeout/resultado desconhecido vira `Ambiguous`; Send não recebe retry cego e reconciliação é obrigatória;
- tentativa ambígua só pode ser reconciliada pelo mesmo provider que a iniciou;
- `Retry-After`/backoff com jitter ficam limitados a leituras/chunks idempotentes e falhas explicitamente transitórias;
- revogação OAuth vira `AUTH_REVOKED`; nenhum token/corpo de erro Graph entra em log/auditoria.
- Gmail usa public client, navegador do sistema, loopback IPv4 aleatório e PKCE S256; não existe client secret, senha, webview ou fluxo OOB;
- o único escopo Google é `gmail.compose`; acesso geral `mail.google.com`, `gmail.modify` e leitura de caixa não são solicitados;
- token Google fica em chave própria do cofre nativo e refresh revogado remove o cache antes de qualquer chamada Gmail;
- Gmail força destino controlado único, Cc vazio, assunto identificado e frases `CONFIRMAR GMAIL N`/`CONFIRMAR GMAIL LOTE G N`;
- Test/Send Gmail exigem `email.send`, MFA, versão e kill switches local/remoto separados dos controles Graph;
- MIME UTF-8 e anexos são montados em Infrastructure; tamanho e SHA-256 são relidos antes da criação do draft;
- `Message-Id` estável permite reaproveitar draft sem duplicar; criação/Send não recebem retry cego;
- após timeout de Send, draft ausente continua `Ambiguous` em vez de pedir leitura ampla da caixa ou presumir entrega;
- resposta/token/código OAuth nunca é incluído em auditoria ou mensagem funcional.
- erros HTTP usam correlação opaca e resposta genérica; query/body não entram no log de aplicação;
- relatos de incidente redigem e-mail, CPF/CNPJ, JWT, bearer, senha e tokens antes da persistência;
- incidentes vinculam tentativa, possuem transições/versionamento e histórico append-only sem exclusão pela interface;
- cópia do catálogo usa AES-256-GCM com PBKDF2-HMAC-SHA-256 (600 mil iterações), sal/nonce aleatórios e envelope autenticado;
- a senha da cópia não é persistida e falha de senha/adulteração não revela qual validação falhou;
- em macOS/Linux, o diretório local próprio recebe modo de usuário `0700` e o SQLite `0600`; no Windows aplica-se o isolamento do perfil CurrentUser;
- o atualizador fica atrás de `IAppUpdateService`, inicia antes da UI e não aplica pacote automaticamente na abertura;
- feeds de produção aceitam somente HTTPS sem userinfo, query ou fragmento; caminho local exige opt-in explícito de validação;
- canais separam plataforma, arquitetura e maturidade; downgrade automático é recusado;
- falha de checksum/corrupção bloqueia a aplicação e retorna mensagem sem detalhes internos;
- stable não pode ser empacotado sem assinatura; CI assinada usa environments protegidos e secrets nunca entram no repositório/binário;
- `minimum_supported_version` e o kill switch global de Send são aplicados pelo servidor também sobre Graph/Gmail;
- repositório GitHub privado não implica token no Desktop: o feed final será HTTPS somente leitura e independente de credencial pessoal.
- no piloto, `Send` é removido da interface e recusado antes da composição, antes da execução e no preflight central;
- `Phase11:AllowSend=false` prevalece sobre switches F7/F8/F10; `Test` e `Draft` têm permissões próprias;
- a política F11 aceita somente ambiente `staging`, exige dados não produtivos e limita o piloto a cinco clientes;
- checklist e auditoria do piloto são isolados por organização; somente contagens agregadas entram nas métricas;
- tentativa Send, ambiguidade/falha pendente ou ocorrência alta/crítica revoga a prontidão;
- o template de staging contém apenas placeholders e switches fechados; credenciais continuam no cofre do ambiente.
- a política F12 é obrigatória para `Send` externo e nasce `Closed`; nenhum appsetting pode desativar sua fiscalização;
- a prontidão cruza piloto aceito, stable assinada, drill de restauração, monitoramento, incidentes, suporte, versão e kill switches;
- somente `Manager`, `Administrator` e `OwnerTechnical`, com `email.send`, MFA e sessão ativa, podem passar pelo preflight de produção;
- limite por lote e cota diária por organização são impostos centralmente antes do provider, em transação serializável;
- autorização de produção é append-only e idempotente por organização/operação; divergência de fingerprint/provider/lote/anexos é recusada;
- o fingerprint armazenado é SHA-256; política e auditoria não expõem destinatário, conteúdo ou fingerprint;
- a UI apenas apresenta bloqueios e remove `Send`; não há botão local para abrir produção;
- `appsettings.Production.json` e o template de ambiente mantêm provider/F10/F12 fechados e não contêm segredo.

## Segredos e dados

O repositório e os appsettings não contêm credenciais. `ISecretStore` persiste access/refresh tokens do app e cache MSAL em compartimentos lógicos separados no cofre do usuário/máquina. SQLite não contém colunas de token. A cópia `.fdmbackup` contém somente o catálogo protegido; PDFs, cache, tokens e autorizações não entram nela. O cache e a revisão recuperável ainda contêm campos documentais, destinatários e caminho local, portanto dados reais exigem FileVault/BitLocker, política LGPD e backup separado do acervo.

## Pendências antes de produção/dados reais

- provisionar e ensaiar rotação dos certificados de assinatura/criptografia OIDC de produção;
- TLS/domínio e proteção operacional do banco;
- smoke do DPAPI pelo app empacotado e teste interativo dos dois OAuth/TOTP em estação Windows física (o round-trip CurrentUser já passou no runner Windows);
- consentimento/revogação reais em contas dedicadas Microsoft e Google no macOS/Windows;
- tela de consentimento, domínio/política e verificação do escopo restrito `gmail.compose` antes de distribuição externa;
- backup/restore real do PostgreSQL e do acervo, com mídia/volume criptografado e responsáveis nomeados;
- validar FileVault/BitLocker e política formal de retenção antes de dados reais.
- provisionar certificados Windows/Apple, Xcode completo, notarização, feed HTTPS e smoke de update/reparação assinado nos dois sistemas.
- provisionar staging HTTPS separado e executar o roteiro físico F11 em macOS e Windows com evidências redigidas;
- homologar contabilmente o corpus anonimizado antes de qualquer dado operacional e obter base legal/política LGPD formal.
- obter aceite formal do piloto e executar a abertura `Limited` conforme `docs/PRODUCTION.md`, com monitoramento, suporte e autoridade de kill switch nomeados.

`FakeEmailProvider` continua padrão. Microsoft Graph e Gmail permanecem desabilitados até configuração local e limitados aos gates de conta/destino controlados. Senha de e-mail, conta compartilhada, confirmação de entrega e produção operacional continuam inexistentes. Veja `docs/PILOT.md`, `docs/PRODUCTION.md`, `docs/THREAT_MODEL.md`, ADR-0007, ADR-0008 e ADR-0009.
