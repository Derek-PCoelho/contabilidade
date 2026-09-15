# ADR-0003: Autenticação do app e OAuth das contas de e-mail

- Status: Proposed
- Data: 2026-08-20
- Decisores: proprietário técnico e equipe do projeto

## Contexto

Existem duas relações de identidade distintas: login no Folhas da Michelly e autorização para usar uma conta Gmail/Microsoft 365. Misturá-las ou armazenar senhas de provedores criaria risco de segurança e revogação incompleta. O cliente Desktop é um cliente público e não pode guardar segredo de aplicação de forma confiável.

## Decisão proposta

### Login no Folhas da Michelly

- Usar ASP.NET Core Identity como armazenamento e gestão de usuários, senhas protegidas, recuperação e TOTP/2FA.
- Usar OpenIddict 7.6 estável como servidor/cliente OIDC, evitando protocolo de autenticação proprietário.
- Usar Authorization Code Flow com PKCE pelo navegador do sistema; não usar ROPC, implicit flow ou formulário de senha dentro do Desktop.
- Tratar o Desktop como public client, com access token curto e refresh token rotacionado/revogável.
- Guardar material de sessão apenas pelo `ISecretStore`: Keychain no macOS, Credential Manager/DPAPI no Windows e memória nos testes.
- Aplicar RBAC e isolamento por organização no servidor; esconder botões na UI é apenas conveniência, não autorização.
- Exigir 2FA para papéis de administração e envio, com política final definida na Fase 2.

### Conexão de e-mail

- Manter OAuth Google/Microsoft separado do login do app.
- Microsoft: MSAL + Microsoft Graph v1.0 com OAuth delegado e menor conjunto de scopes.
- Google: Gmail API + OAuth para aplicativo instalado, navegador do sistema e redirect permitido pelo provedor.
- Nunca armazenar senha, client secret de public client ou token em arquivo JSON/configuração.
- Associar tokens ao usuário e dispositivo; revogação deve ser visível e auditada.
- Nenhuma integração real de e-mail será iniciada antes das Fases 7 e 8 e do `FakeEmailProvider` completo.

## Consequências

- O servidor de identidade passa a ser componente de segurança crítico, exigindo proteção de chaves, TLS, rate limit, auditoria, atualização e backup.
- OpenIddict reduz código protocolar próprio, mas a integração Avalonia/macOS com callback precisa de prova de conceito.
- Um provedor OIDC gerenciado continua alternativa válida se o custo operacional do self-hosted se mostrar alto.

## Gate para Accepted

Na Fase 2, provar em dados sintéticos:

1. login por navegador do sistema com PKCE em macOS e Windows;
2. 2FA TOTP para papel privilegiado;
3. refresh rotation e revogação de usuário/dispositivo;
4. API rejeita organização fornecida pelo cliente fora do contexto autenticado;
5. nenhum token aparece em banco local textual, log ou repositório.

## Evidência da Fase 2

- servidor e cliente OpenIddict compilam com Authorization Code, PKCE e integração com navegador do sistema;
- TOTP é obrigatório para papéis privilegiados e as políticas de escrita exigem `amr=mfa`;
- tokens e sessões de dispositivo são revogáveis e o teste de API confirma revogação imediata;
- o DTO de sync não contém organização e testes com duas organizações confirmam isolamento;
- SQLite local foi inspecionado por teste e não contém colunas de access/refresh token; o secret store permanece somente em memória.

## Evidência adicional da Fase 7

- `ISecretStore` de runtime usa Keychain no macOS e arquivo protegido por DPAPI CurrentUser no Windows; doubles unitários continuam in-memory e gates nativos dedicados exercitam cada SO;
- o round-trip real do Keychain passou no Mac de desenvolvimento sem gravar segredo em arquivo;
- o round-trip DPAPI CurrentUser passou no runner Windows sem expor o segredo em arquivo legível;
- o cache MSAL v3 da conta Microsoft usa uma chave de cofre diferente da sessão do aplicativo;
- Microsoft Graph usa public client, navegador do sistema, OAuth delegado e escopos separados: `Mail.ReadWrite` para draft/reconciliação e `Mail.Send` apenas quando o kill switch real está habilitado;
- conexão/desconexão de e-mail são comandos e eventos de auditoria distintos do login no Folhas da Michelly;
- revogação é mapeada para `AUTH_REVOKED` e bloqueia a operação sem chamada Graph.

## Evidência adicional da Fase 8

- Google usa Authorization Code para app instalado, navegador do sistema, loopback IPv4 aleatório e PKCE S256, sem client secret, senha ou OOB;
- o único escopo solicitado é `gmail.compose`; não há leitura geral da caixa;
- access/refresh token Google usa chave própria do `ISecretStore`, separada de MSAL e da sessão do aplicativo;
- refresh revogado remove o token local e vira `AUTH_REVOKED`; desconectar tenta revogação remota e sempre encerra a sessão local;
- Gmail REST/MimeKit ficam confinados em Infrastructure; Application mantém contratos próprios;
- OAuth, MIME, destino controlado, draft/send, anexos e ambiguidade foram provados offline sem abrir navegador nem acessar conta real.

O ADR permanece `Proposed`: ainda faltam os smokes interativos reais dos fluxos OAuth em macOS e Windows, consentimento/revogação em contas dedicadas, verificação do escopo Google e o smoke do cofre pelo aplicativo empacotado em estação Windows física. Tokens agora podem persistir no cofre nativo, mas dados reais continuam proibidos até os gates de dispositivo/LGPD e hardening.

## Alternativas consideradas

- Identity com endpoints/tokens próprios sem OIDC: não selecionada por interoperabilidade menor.
- Provedor OIDC gerenciado: adiado até conhecer orçamento, domínio e requisitos operacionais.
- Autenticação caseira, ROPC ou senha de e-mail: rejeitadas.

## Referências

- https://learn.microsoft.com/en-us/aspnet/core/security/authentication/mfa?view=aspnetcore-10.0
- https://documentation.openiddict.com/guides/choosing-the-right-flow.html
- https://documentation.openiddict.com/configuration/proof-key-for-code-exchange
- https://documentation.openiddict.com/integrations/operating-systems
- https://developers.google.com/identity/protocols/oauth2/native-app
- https://developers.google.com/workspace/gmail/api/auth/scopes
