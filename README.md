# Folhas da Michelly

Aplicativo desktop Avalonia + backend ASP.NET Core para processamento contábil seguro e auditável. A Fase 12 prepara uma produção gradual com papel/MFA, cotas centrais, versão mínima e defesa em profundidade, permanecendo fechada até os gates operacionais externos.

O `FakeEmailProvider` local continua sendo o padrão. Enquanto F12 não estiver integralmente pronta, a interface oferece somente Teste e Rascunho e `Send` é bloqueado no Desktop, workflow e servidor. Graph e Gmail nascem desabilitados e dependem de conta/destino controlados. OCR e confirmação de entrega não estão implementados; nenhum deploy ou envio real foi executado.

## Pré-requisitos

- .NET SDK 10.0.400 arm64;
- macOS 26 arm64 ou Windows compatível com Avalonia 12;
- PostgreSQL 18 para executar o backend com persistência de produção;
- Git.

## Gates

```bash
dotnet restore --locked-mode
dotnet format FolhasDaMichelly.slnx --no-restore --verify-no-changes
dotnet build FolhasDaMichelly.slnx -c Release --no-restore
dotnet test FolhasDaMichelly.slnx -c Release --no-build --no-restore
```

Para o teste real Npgsql, defina `FOLHAS_TEST_POSTGRES` para um banco descartável de testes. O teste recria esse banco.

As suítes `MicrosoftGraphLive` e `GmailLive` são opt-in e permanecem sem tráfego quando suas variáveis `FOLHAS_*_LIVE_TEST` não são `true`. Consulte [providers](docs/EMAIL_PROVIDERS.md) antes de habilitá-las.

## Execução de desenvolvimento

O servidor usa PostgreSQL e inicializa migrations/seeds no ambiente Development. Configure a connection string sem versionar segredo:

```bash
export ConnectionStrings__CentralDatabase='Host=127.0.0.1;Port=5432;Database=folhas_development;Username=SEU_USUARIO'
dotnet run --project src/FolhasDaMichelly.Server
dotnet run --project src/FolhasDaMichelly.Desktop
```

Endpoints principais:

- `GET /health/live` e `GET /health/ready`;
- `POST /api/document-recognition/resolve-client` para resolução tenant-safe sem upload do PDF;
- `GET|POST /api/sync/clients` com autenticação/RBAC;
- `GET|POST|PUT /api/clients` para o cadastro central e elegibilidade;
- `GET|POST|PUT /api/message-templates` para modelos sem envio;
- `GET /api/clients/catalog/export` e `POST /api/clients/catalog/import` para JSON versionado;
- `/connect/authorize` e `/connect/token` para OIDC;
- `POST /api/email-dispatch/preflight` para autorizar/auditar qualquer saída Graph/Gmail (`Test`/`Send`) antes do provider;
- `GET /api/pilot/policy` para consultar a política não secreta de homologação;
- `GET /api/production/policy` para consultar, com `email.send` + MFA, limites, cota agregada e bloqueios F12;
- `/hubs/sync` para notificações por organização.

O esquema de autenticação por headers sintéticos é restrito a Development/Testing e deve permanecer desativado fora de testes controlados.

## Piloto supervisionado da Fase 11

Use somente dados sintéticos ou formalmente anonimizados, no máximo cinco clientes e uma beta assinada. O painel em **Configurações** acompanha os controles macOS/Windows, backup/restore, rollback e contas de teste sem registrar PII. A configuração segura de staging está em `deploy/staging/.env.example`; o roteiro e os critérios de parada estão em [piloto](docs/PILOT.md).

```bash
export FOLHAS_STAGING_BASE_URL='https://homologacao.exemplo.invalid'
export FOLHAS_STAGING_ACCESS_TOKEN='TOKEN_DO_COFRE_DA_SESSAO'
bash tools/pilot/verify-staging.sh
```

Não grave o token no shell history ou em arquivo do projeto. Sem staging, certificados e smokes físicos, mantenha o checklist pendente; isso é um bloqueio explícito, não uma falha a contornar.

## Produção gradual da Fase 12

A configuração segura está em `deploy/production/.env.example` com todos os switches fechados. A aplicação exige aceite do piloto, stable assinada, drill de backup/restauração, monitoramento, incidentes e suporte, além de papel privilegiado, MFA, limite por lote/dia e autorização central idempotente. O painel em **Configurações** é somente leitura e não abre produção localmente.

O procedimento, a ordem de abertura e o kill switch estão em [produção gradual](docs/PRODUCTION.md). Sem as evidências externas, permaneça em `Closed`; não copie o template para um ambiente real preenchendo confirmações que não ocorreram.

## Microsoft Graph da Fase 7

Somente valores não secretos entram na configuração local; não versione endereço real nem tenant privado:

```bash
export Phase7__ProviderKey='microsoft.graph'
export Phase7__MicrosoftGraph__Enabled='true'
export Phase7__MicrosoftGraph__ClientId='00000000-0000-0000-0000-000000000000'
export Phase7__MicrosoftGraph__TenantId='organizations'
export Phase7__MicrosoftGraph__RedirectUri='http://localhost'
export Phase7__MicrosoftGraph__ControlledRecipient='DESTINO_CONTROLADO'
export Phase7__MicrosoftGraph__EmailSendEnabled='false'
```

Com `EmailSendEnabled=false`, o consentimento pede somente `Mail.ReadWrite`; `Mail.Send` só é solicitado quando o kill switch é habilitado antes da conexão. Não use client secret: o Desktop é public client. O token MSAL é separado do login do aplicativo e persiste no Keychain (macOS) ou em arquivo protegido por DPAPI CurrentUser (Windows).

## Gmail API da Fase 8

Use somente Client ID do tipo aplicativo Desktop, conta Google dedicada e destino controlado. Não existe client secret:

```bash
export Phase8__ProviderKey='google.gmail'
export Phase8__MinimumSendVersion='0.8.0'
export Phase8__Gmail__Enabled='true'
export Phase8__Gmail__ClientId='CLIENT_ID.apps.googleusercontent.com'
export Phase8__Gmail__ControlledRecipient='DESTINO_CONTROLADO'
export Phase8__Gmail__EmailSendEnabled='false'
```

O navegador do sistema usa loopback + PKCE. O único escopo é `gmail.compose`; o refresh token persiste no mesmo cofre nativo, em compartimento separado. Distribuição externa depende da configuração/verificação Google documentada em [providers](docs/EMAIL_PROVIDERS.md).

## Segurança e escopo

Não adicione PDFs contábeis, planilhas operacionais, CPFs/CNPJs, e-mails, tokens ou credenciais reais sem escopo e autorização expressos; tokens, senhas e segredos nunca são versionados. Testes usam somente documentos e domínios sintéticos visivelmente marcados. Assets e conteúdos fornecidos pelo proprietário podem integrar o Git e o aplicativo quando a autorização, a origem e a finalidade estiverem registradas. SQLite local guarda cache/checkpoint/fila/reconhecimento, nunca tokens; sessões ficam no cofre nativo por usuário e máquina.

Leia [AGENTS.md](AGENTS.md), [arquitetura](docs/ARCHITECTURE.md), [reconhecimento documental](docs/DOCUMENT_RECOGNITION.md), [validação e agrupamento](docs/VALIDATION_AND_GROUPING.md), [providers](docs/EMAIL_PROVIDERS.md), [segurança](docs/SECURITY.md), [sincronização](docs/SYNC.md), [modelo de dados](docs/DATA_MODEL.md), [produção](docs/PRODUCTION.md) e [plano](docs/EXECUTION_PLAN.md) antes de alterar gates operacionais.
