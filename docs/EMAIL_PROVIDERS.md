# Providers de e-mail — baseline da Fase 8

## Fronteira e seleção

`IEmailProvider` expõe conta, capacidades, criação de draft, envio e reconciliação. Application depende somente do contrato próprio; MSAL, OAuth Google, MIME e HTTP dos provedores ficam em Infrastructure.

Providers implementados:

- `fake.local`: padrão, sem socket, recibos JSON locais e cenários determinísticos da Fase 6;
- `microsoft.graph`: opt-in, Graph v1.0, conta Microsoft 365 dedicada e destino controlado obrigatório;
- `google.gmail`: opt-in, Gmail API v1, OAuth para app instalado, MIME e destino controlado obrigatório.

Configuração desconhecida falha na inicialização. Graph/Gmail desabilitado ou sem `ClientId`/destino controlado bloqueia a composição/execução; nunca há fallback silencioso para envio.

## OAuth Microsoft separado

O login no Folhas da Michelly e a conta de e-mail são sessões distintas. O Desktop é public client, usa MSAL 4.88.0 e navegador do sistema, sem client secret e sem senha Microsoft.

Escopos delegados:

- draft, anexo e reconciliação: `Mail.ReadWrite`;
- envio: `Mail.ReadWrite` + `Mail.Send`, solicitado somente quando `Phase7__MicrosoftGraph__EmailSendEnabled=true` antes da conexão.

O cache MSAL v3 usa `email-provider/microsoft-graph/msal-v3-cache` no `ISecretStore`, separado das chaves `app-session/*`. Desconectar remove contas MSAL e cache local e grava evento auditável.

No registro do aplicativo Microsoft Entra:

1. configure public client para desktop;
2. registre exatamente o redirect loopback `http://localhost` usado pelo aplicativo;
3. conceda permissões delegadas mínimas, nunca application permissions;
4. limite usuários/consentimento à conta de teste conforme a política do tenant;
5. não crie nem distribua client secret para o Desktop;
6. conta compartilhada permanece fora do escopo.

## OAuth Google separado e escopo mínimo

O Gmail usa Authorization Code para aplicativo instalado, navegador do sistema, callback aleatório em `http://127.0.0.1:{porta}/oauth2/callback/` e PKCE S256. O Desktop é public client: não existe `client_secret`, formulário de senha embutido nem fluxo OOB de copiar/colar código.

O único escopo solicitado é:

```text
https://www.googleapis.com/auth/gmail.compose
```

Ele é necessário para localizar/criar drafts e enviar o draft aprovado. Não são solicitados `https://mail.google.com/`, `gmail.modify` ou `gmail.readonly`. Como `gmail.compose` é classificado pelo Google como escopo restrito, distribuição externa exige tela de consentimento, política de privacidade, domínio verificado e o processo de verificação aplicável; armazenamento/transmissão server-side futura pode exigir avaliação de segurança adicional. Até esse gate, somente usuários de teste de uma conta/projeto dedicado são permitidos.

Access/refresh token, expiração, escopo e identidade da conta ficam juntos na chave lógica `email-provider/google-gmail/oauth-v1-token` do `ISecretStore`. Não vão para SQLite, appsettings ou Git. Refresh revogado remove imediatamente o cache e retorna `AUTH_REVOKED`. Desconectar tenta revogar remotamente e sempre encerra a sessão local; falha de rede na revogação deve ser tratada operacionalmente no painel da conta Google.

## Configuração fail-closed

Use configuração local/variável de ambiente e não versione valores reais:

```text
Phase7__ProviderKey=microsoft.graph
Phase7__MicrosoftGraph__Enabled=true
Phase7__MicrosoftGraph__ClientId=<application-client-id>
Phase7__MicrosoftGraph__TenantId=<tenant-id-ou-organizations>
Phase7__MicrosoftGraph__RedirectUri=http://localhost
Phase7__MicrosoftGraph__ControlledRecipient=<caixa-de-teste-controlada>
Phase7__MicrosoftGraph__EmailSendEnabled=false
Phase7__MicrosoftGraph__MaximumRetryAttempts=3

Phase8__ProviderKey=google.gmail
Phase8__MinimumSendVersion=0.8.0
Phase8__Gmail__Enabled=true
Phase8__Gmail__ClientId=<desktop-client-id>.apps.googleusercontent.com
Phase8__Gmail__ControlledRecipient=<caixa-de-teste-controlada>
Phase8__Gmail__EmailSendEnabled=false
Phase8__Gmail__MaximumRetryAttempts=3
```

`ClientId` e `TenantId` não são segredos, mas podem identificar o ambiente e não devem ser incluídos em fixtures públicas. O endereço controlado também não deve entrar no Git.

## Destinatários e confirmação humana

Enquanto durar a Fase 7, todo modo Graph — Test, Draft ou Send — substitui o destino efetivo pelo único `ControlledRecipient` e remove Cc. A rota contábil original permanece visível no snapshot/relatório para conferência, mas não chega ao Graph. O assunto recebe o prefixo `FASE 7 — DESTINO CONTROLADO`.

O provider revalida novamente a lista efetiva antes de qualquer HTTP. Como `Test` também envia uma mensagem real à caixa controlada, `Test` e `Send` exigem `email.send`, kill switches local/remoto, versão mínima, aprovação atual e preflight central. A confirmação digitada continua exclusiva de `Send`:

```text
individual: CONFIRMAR GRAPH N
lote:       CONFIRMAR GRAPH LOTE G N
```

O kill switch Graph nasce desligado. Cenários `TransientFailure`, `Timeout` etc. pertencem somente ao Fake e são rejeitados no Graph.

Imediatamente antes de qualquer saída Graph (`Test` ou `Send`), o Desktop chama `POST /api/email-dispatch/preflight`. A API exige `email.send`, MFA e sessão de dispositivo ativa, confere kill switch/versão mínima do servidor e grava auditoria central sem destinatário, assunto ou fingerprint. Resposta incoerente, falha de rede/banco, 401/403 ou negativa remota bloqueia antes de criar `DeliveryAttempt` ou chamar o Graph.

As mesmas regras valem para Gmail, com destino controlado único, Cc vazio e prefixo `FASE 8 — DESTINO CONTROLADO`. `Test` é uma saída real para a caixa controlada e exige os dois kill switches e preflight; `Send` exige também:

```text
individual: CONFIRMAR GMAIL N
lote:       CONFIRMAR GMAIL LOTE G N
```

O servidor mantém kill switch e versão mínima separados em `Phase8:Gmail`, impedindo que habilitar Graph habilite Gmail por consequência.

## Draft-first, anexos e idempotência

O envio real nunca usa `sendMail` direto. O adaptador:

1. procura uma mensagem pela propriedade estendida estável `FolhasIdempotency`;
2. cria draft com `Prefer: IdType="ImmutableId"` quando ainda não existe;
3. relê SHA-256 e tamanho de todo anexo antes do upload;
4. adiciona arquivo menor que 3 MiB como `fileAttachment`;
5. usa `createUploadSession` e chunks múltiplos de 320 KiB para arquivos maiores;
6. chama `POST /me/messages/{immutable-id}/send` somente após draft/anexos;
7. persiste `AcceptedByProvider` para HTTP 202 — nunca `Delivered`.

O `contentId` derivado do hash permite retomar anexo sem duplicá-lo. A chave externa é estável por item/modo/fingerprint; tentativas locais recebem chaves únicas para preservar histórico.

## Falhas, retry e reconciliação

- GETs e PUTs de chunk idempotentes repetem 429/408/5xx com `Retry-After` ou backoff exponencial + jitter, com limite configurado;
- criação de draft/anexo e `send` não recebem retry HTTP cego;
- 401 vira `AUTH_REVOKED`; 400/403/413/415/422 vira `PROVIDER_REJECTED`; 429 vira `PROVIDER_THROTTLED`; 404/409 após operação potencialmente aceita permanece ambíguo;
- timeout, falha de transporte ou 5xx em operação que pode ter sido aceita vira `Ambiguous`;
- `Pending/Ambiguous` impedem nova tentativa até `Reconcile`;
- reconciliação exige o mesmo provider da tentativa e procura o ID imutável: `isDraft=false` confirma aceite; `isDraft=true` confirma que não enviou e permite nova execução somente por ação humana (`Send` pede outra frase);
- mensagem ausente permanece ambígua; não autoriza retry.

IDs imutáveis continuam válidos quando o Exchange move o draft para Sent Items dentro da mesma caixa. A criação da cópia enviada pode ter consistência eventual, então uma reconciliação inicialmente ausente continua bloqueada.

## Gmail MIME, idempotência e resultado ambíguo

O adaptador Gmail usa REST v1 via `HttpClient` e MimeKit 4.17.0 para produzir mensagem RFC/MIME em UTF-8 com texto, HTML codificado e anexos PDF. O SDK `Google.Apis.Gmail.v1` foi avaliado e não instalado: o subconjunto usado é pequeno, e o adaptador próprio permite conectar o OAuth diretamente ao cofre nativo e testar a política de retry sem armazenamento em arquivo.

O fluxo Gmail é draft-first:

1. calcula `Message-Id` estável por modo/item/fingerprint e procura somente drafts com `rfc822msgid` usando `gmail.compose`;
2. relê tamanho e SHA-256 de cada anexo;
3. cria `users/me/drafts` com MIME base64url se ainda não existir;
4. persiste IDs de draft/message retornados;
5. envia somente o ID do draft por `users/me/drafts/send`;
6. sucesso vira `AcceptedByProvider`, nunca `Delivered`.

GETs de draft podem repetir 408/429/5xx com `Retry-After`/backoff limitado. Criação e Send nunca recebem retry cego. Se Send perde a resposta, a existência do draft prova “ainda não enviado”; draft ausente não prova envio, pois também pode ter sido removido, e permanece `Ambiguous`. Não foi pedido escopo amplo de leitura só para fechar essa incerteza.

## FakeEmailProvider preservado

`FakeEmailProvider` continua sem `HttpClient`, grava apenas nome/tamanho/hash dos anexos e cobre success, transient, permanent, timeout e ambiguous. Test exige `example.invalid`; Draft e Send permanecem simulações locais. Ele é o caminho padrão para desenvolvimento e para toda suíte sem opt-in.

## Gate live opt-in

`Phase7MicrosoftGraphLiveTests` tem trait `MicrosoftGraphLive` e retorna sem rede por padrão. Para executá-lo conscientemente:

1. configure o Desktop com os mesmos ClientId/tenant/destino;
2. conecte a conta Microsoft 365 de teste pelo botão `Conectar Graph`;
3. defina `FOLHAS_GRAPH_LIVE_TEST=true`, `FOLHAS_GRAPH_CLIENT_ID`, `FOLHAS_GRAPH_TENANT_ID` e `FOLHAS_GRAPH_CONTROLLED_RECIPIENT`;
4. sem `FOLHAS_GRAPH_LIVE_SEND=true`, o teste cria e remove somente um draft sintético;
5. `FOLHAS_GRAPH_LIVE_SEND=true` executa um Send para o destino controlado e deve ser usado apenas no gate manual protegido.

`Phase8GmailLiveTests` segue o mesmo modelo:

1. configure e conecte pelo Desktop uma conta Google dedicada no cofre local;
2. defina `FOLHAS_GMAIL_LIVE_TEST=true`, `FOLHAS_GMAIL_CLIENT_ID` e `FOLHAS_GMAIL_CONTROLLED_RECIPIENT`;
3. sem `FOLHAS_GMAIL_LIVE_SEND=true`, cria/anexa/remove somente um draft sintético;
4. a variável adicional autoriza exclusivamente um Send sintético ao destino controlado.

Referências oficiais: [criar mensagem](https://learn.microsoft.com/graph/api/user-post-messages?view=graph-rest-1.0), [enviar draft](https://learn.microsoft.com/graph/api/message-send?view=graph-rest-1.0), [upload de anexo](https://learn.microsoft.com/graph/api/attachment-createuploadsession?view=graph-rest-1.0), [IDs imutáveis](https://learn.microsoft.com/graph/outlook-immutable-id) e [throttling](https://learn.microsoft.com/graph/throttling).

Referências Google: [OAuth para apps instalados](https://developers.google.com/identity/protocols/oauth2/native-app), [escopos Gmail](https://developers.google.com/workspace/gmail/api/auth/scopes), [drafts.list](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.drafts/list) e [drafts.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.drafts/send).
