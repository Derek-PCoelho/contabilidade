# MEGA PROMPT PARA O CODEX - FOLHAS DA MICHELLY — v1.0

## Especificação mestre cross-platform, sincronizada e auditável

**Versão:** 1.0  
**Data-base:** 20 de agosto de 2026  
**Nome do produto:** Folhas da Michelly  
**Plataformas:** Windows e macOS  
**Stack-base:** C# / .NET 10 LTS / Avalonia UI / ASP.NET Core / SQLite / PostgreSQL  
**Objetivo:** construir o produto em fases verificáveis, sem envio real durante as fases iniciais

---

## 1. Como este arquivo deve ser interpretado

Este arquivo é a **especificação normativa técnica** do projeto. O arquivo `BLUEPRINT_PROJETO_FOLHAS_DA_MICHELLY.md` é a fonte funcional e de produto. Leia os dois integralmente antes de implementar.

Ordem de precedência:

1. segurança e integridade de dados;
2. este Mega Prompt;
3. Blueprint;
4. ADRs aprovados no repositório;
5. plano de execução da fase atual;
6. decisões locais de implementação.

Se existir conflito relevante entre Mega Prompt e Blueprint, **não escolha silenciosamente**. Registre o conflito, proponha a solução em um ADR e prossiga somente quando a escolha não ampliar risco. Para detalhes triviais de implementação, escolha a alternativa mais simples, testável e cross-platform.

### Regra sobre versões

A data-base usa .NET 10 LTS. Descubra no início do trabalho as versões estáveis atuais do .NET, Avalonia e bibliotecas. Não use preview em produção. Não congele versões neste documento; fixe versões estáveis no repositório, use lockfiles quando aplicável e documente atualizações.

---

# INÍCIO DO PROMPT MESTRE

## 2. Papel que você, Codex, deve assumir

Atue simultaneamente como:

- arquiteto de software sênior;
- engenheiro C#/.NET cross-platform;
- especialista em Avalonia UI e MVVM;
- engenheiro de backend ASP.NET Core;
- especialista em PostgreSQL e SQLite;
- engenheiro de segurança e privacidade;
- especialista em OAuth 2.0, Gmail API e Microsoft Graph;
- especialista em CI/CD, distribuição desktop e atualização automática;
- engenheiro de qualidade e testes;
- analista de processos contábeis para entender o formato dos documentos, **sem se declarar substituto do contador ou do sistema contábil**.

Seu objetivo é construir software profissional, previsível, auditável, seguro e fácil de manter.

---

## 3. Regras de execução inegociáveis

- Leia integralmente este arquivo e o Blueprint antes de editar código.
- Inspecione o repositório antes de criar estruturas paralelas.
- Preserve trabalho válido existente.
- Trabalhe por fases e commits pequenos/revisáveis.
- Mantenha o repositório compilável ao final de cada etapa coerente.
- Crie e mantenha `docs/EXECUTION_PLAN.md` e `docs/PROGRESS.md`.
- Crie `AGENTS.md` curto na raiz com comandos, convenções e definição de pronto.
- Registre decisões arquiteturais em `docs/adr/ADR-XXXX-*.md`.
- Execute build, testes, lint/analyzers e informe resultados reais.
- Nunca afirme que testou algo que não executou.
- Não use dados operacionais/de clientes reais nem credenciais nos testes automatizados. Assets expressamente autorizados pelo proprietário podem ter testes de integridade e empacotamento.
- Não envie e-mails reais durante Fases 0-6.
- Não coloque PDFs, CPFs/CNPJs ou planilhas operacionais reais no Git sem autorização nominal, finalidade e revisão de segurança; tokens, credenciais e senhas nunca são versionados.
- Não use Selenium, Playwright, `pyautogui`, AppleScript/AutoHotkey ou automação de tela para webmail.
- Use APIs oficiais de e-mail e OAuth.
- Não armazene senha de Gmail/Outlook.
- Não faça IA escolher destinatário ou cliente automaticamente.
- Não corrija conteúdo contábil do PDF de forma automática.
- Não trate correspondência fuzzy de nome como autorização para envio.
- Não trate `202 Accepted` do Microsoft Graph como prova de entrega final.
- Não adicione dependência GPL/AGPL, preview, abandonada ou com licença incompatível sem parar e justificar.
- Não introduza telemetria externa com conteúdo sensível.
- Qualquer ação destrutiva ou publicação de produção exige confirmação humana.

### Saída esperada ao finalizar cada fase

Informe:

1. objetivo alcançado;
2. arquivos criados/alterados;
3. comandos executados;
4. testes e resultados;
5. decisões/ADRs;
6. riscos e pendências;
7. próximos passos.

---

## 4. Objetivo funcional

Criar um aplicativo instalado chamado **Folhas da Michelly** capaz de receber aproximadamente 100 ou mais clientes e centenas de documentos por ciclo, reconhecer a quem cada arquivo pertence, conferir consistência, agrupar documentos, gerar mensagens, permitir revisão individual ou em lote, enviar via Gmail/Microsoft 365, sincronizar cadastros entre máquinas, registrar auditoria e distribuir atualizações do próprio aplicativo.

Fluxo central:

```text
ENTRADA DE ARQUIVOS
  -> HASH E LEITURA
  -> CLASSIFICAÇÃO
  -> EXTRAÇÃO SEMÂNTICA
  -> RESOLUÇÃO DO CLIENTE
  -> VALIDAÇÕES
  -> AGRUPAMENTO
  -> COMPOSIÇÃO DO E-MAIL
  -> REVISÃO INDIVIDUAL OU LOTE
  -> TESTE / RASCUNHO / ENVIO
  -> AUDITORIA
  -> ORGANIZAÇÃO DOS ARQUIVOS
  -> RELATÓRIO
```

Prioridade absoluta: **zero mistura de documentos entre clientes**.

---

## 5. Arquitetura aprovada

Use a seguinte arquitetura, salvo impossibilidade técnica demonstrada por prova de conceito e ADR.

| Área | Decisão |
|---|---|
| Linguagem | C# estável compatível com .NET 10 LTS |
| Desktop | Avalonia UI, versão estável atual, padrão MVVM |
| MVVM | CommunityToolkit.Mvvm ou alternativa permissiva justificada |
| Backend | ASP.NET Core Web API |
| Banco central | PostgreSQL |
| Banco local | SQLite via Entity Framework Core |
| ORM servidor | EF Core + provider PostgreSQL estável |
| Tempo real | ASP.NET Core SignalR |
| Auth do app | ASP.NET Core Identity/OIDC modular, com RBAC; escolha final em ADR |
| E-mail Microsoft | Microsoft Graph v1.0 + OAuth delegado/MSAL |
| E-mail Google | Gmail API + OAuth 2.0 para app instalado |
| MIME | MimeKit, se necessário e compatível |
| PDF | abstração própria + biblioteca cross-platform estável/permissiva |
| XLSX | ClosedXML ou biblioteca equivalente permissiva e auditada |
| Logs | Microsoft.Extensions.Logging + sink estruturado com redaction |
| Atualização | Velopack ou equivalente cross-platform aprovado |
| CI | GitHub Actions com runners Windows e macOS |
| Código | Git privado + branches/PRs |
| Distribuição | pacote Windows e bundle/DMG macOS assinados conforme maturidade |

### 5.1 Por que não WPF

WPF é Windows-only. O requisito passou a incluir macOS; não utilize WPF no novo projeto.

### 5.2 Target frameworks e RIDs

Comece com `net10.0` para projetos compartilhados/desktop quando compatível.

RIDs de release iniciais:

- `win-x64` obrigatório;
- `osx-arm64` obrigatório;
- `osx-x64` recomendado;
- `win-arm64` opcional após estabilização.

Não espalhe `if (OperatingSystem.IsWindows())` por toda a aplicação. Centralize integrações específicas de plataforma em serviços atrás de interfaces.

---

## 6. Estrutura esperada do repositório

```text
FolhasDaMichelly/
├── .github/
│   └── workflows/
│       ├── ci.yml
│       ├── release.yml
│       └── security.yml
├── assets/
│   └── brand/
│       ├── source/
│       └── generated/
├── docs/
│   ├── specs/
│   │   ├── BLUEPRINT_PROJETO_FOLHAS_DA_MICHELLY.md
│   │   └── MEGA_PROMPT_FOLHAS_DA_MICHELLY_CODEX.md
│   ├── adr/
│   ├── EXECUTION_PLAN.md
│   ├── PROGRESS.md
│   ├── ARCHITECTURE.md
│   ├── DATA_MODEL.md
│   ├── SECURITY.md
│   ├── DOCUMENT_RECOGNITION.md
│   ├── EMAIL_PROVIDERS.md
│   ├── SYNC.md
│   ├── RELEASES.md
│   ├── OPERATIONS.md
│   └── TEST_PLAN.md
├── src/
│   ├── FolhasDaMichelly.Domain/
│   ├── FolhasDaMichelly.Application/
│   ├── FolhasDaMichelly.Contracts/
│   ├── FolhasDaMichelly.Infrastructure/
│   ├── FolhasDaMichelly.Desktop/
│   └── FolhasDaMichelly.Server/
├── tests/
│   ├── FolhasDaMichelly.Domain.Tests/
│   ├── FolhasDaMichelly.Application.Tests/
│   ├── FolhasDaMichelly.Infrastructure.Tests/
│   ├── FolhasDaMichelly.Server.Tests/
│   ├── FolhasDaMichelly.Ui.Tests/
│   └── Fixtures/
│       └── Synthetic/
├── tools/
├── Directory.Build.props
├── Directory.Packages.props
├── global.json
├── .editorconfig
├── .gitignore
├── AGENTS.md
├── README.md
└── FolhasDaMichelly.slnx ou .sln
```

### Dependências de projeto

- `Domain`: não depende de Infrastructure, Desktop ou Server.
- `Application`: depende de Domain e contratos/abstrações.
- `Contracts`: DTOs de API e mensagens de sincronização, sem lógica de infraestrutura.
- `Infrastructure`: implementa persistência, parsers, e-mail, arquivos, secret store, updater.
- `Desktop`: UI + composição, depende de Application e abstrações.
- `Server`: API + auth + persistência central + SignalR.

Evite uma “God project” com tudo no executável.

---

## 7. Modelo de dados central

Projete primeiro o domínio e só depois o banco.

### 7.1 Organization

- `Id` UUID;
- `Name`;
- `Slug`;
- `IsActive`;
- `CreatedAtUtc`;
- `UpdatedAtUtc`.

Mesmo que inicialmente exista um escritório, manter `OrganizationId` nas entidades compartilhadas reduz risco de mistura futura.

### 7.2 AppUser

- ID;
- OrganizationId;
- nome;
- e-mail;
- status;
- papel(is);
- preferências;
- último acesso;
- versão para concorrência.

### 7.3 Role/Permission

Papéis mínimos:

- OwnerTechnical;
- Administrator;
- Manager;
- Operator;
- Auditor.

Permissões granulares mínimas:

- `clients.read/write`;
- `templates.read/write`;
- `documents.process`;
- `batch.approve`;
- `email.draft`;
- `email.send`;
- `audit.read/export`;
- `users.manage`;
- `recognition.manage`;
- `settings.manage`.

### 7.4 Client

- Id;
- OrganizationId;
- PersonType: PF/PJ;
- LegalNameOrFullName;
- PreferredName nullable;
- InternalCode nullable;
- PrimaryTaxIdNormalized;
- IsActive;
- DefaultSubjectTemplateId nullable;
- DefaultBodyTemplateId nullable;
- Notes nullable;
- CreatedBy/UpdatedBy;
- timestamps;
- concurrency token.

### 7.5 ClientIdentifier

- Id;
- ClientId;
- Type: CNPJ, CNPJ_ROOT, CPF, INTERNAL_CODE, LEGAL_NAME_ALIAS, OTHER;
- ValueNormalized;
- SemanticRole;
- Priority;
- IsActive;
- IsUniqueWithinOrganization.

Índice único apropriado para identificadores que precisam ser exclusivos.

### 7.6 Establishment

- Id;
- ClientId;
- CnpjNormalized;
- CnpjRoot;
- LegalName;
- DisplayName;
- InternalCode;
- IsHeadOffice;
- IsActive.

### 7.7 Recipient

- Id;
- ClientId;
- EstablishmentId nullable;
- DisplayName;
- EmailNormalized;
- DeliveryRole: To/CC/InternalCopy;
- DocumentTypeId nullable;
- IsPrimary;
- IsActive;
- ValidFrom/ValidTo nullable.

Não assuma que “e-mail secundário = CC” sem permitir configuração.

### 7.8 MessageTemplate

- Id;
- OrganizationId;
- ClientId nullable;
- DocumentTypeId nullable;
- Name;
- SubjectTemplate;
- BodyTemplate;
- SignatureMode;
- IsDefault;
- IsActive;
- Version;
- UpdatedBy.

### 7.9 DocumentType

- Id;
- Code;
- DisplayName;
- Category;
- DefaultGroupingPolicy;
- IsActive.

### 7.10 DocumentRecognitionProfile

- Id;
- DocumentTypeId;
- Name;
- Version;
- RequiredAnchors JSON;
- OptionalAnchors JSON;
- ExcludedAnchors JSON;
- ExtractionRules JSON;
- ValidationRules JSON;
- GroupingRules JSON;
- MinimumConfidence;
- IsActive;
- PublishedAtUtc;
- Signature/Checksum opcional.

Perfis configuráveis devem ser validados no servidor antes da publicação.

### 7.11 ProcessingBatch

- Id;
- OrganizationId;
- CreatedBy;
- CreatedAtUtc;
- Mode: Individual/Batch;
- EmailMode: Test/Draft/Send;
- SelectedPeriod nullable;
- Status;
- WorkstationId;
- AppVersion;
- counters.

### 7.12 ImportedDocument

Metadados centrais e cache local devem ser separados quando necessário.

Campos lógicos:

- Id;
- BatchId;
- OriginalFileName;
- LocalPath: **não sincronizar ao servidor como caminho absoluto**;
- SHA256;
- MIME;
- Length;
- PageCount;
- DocumentTypeId nullable;
- ClientId nullable;
- EstablishmentId nullable;
- confidence;
- temporal context;
- status;
- parser profile/version;
- extracted fields snapshot redigido quando apropriado.

### 7.13 ExtractedField

- DocumentId;
- Key;
- ValueNormalized;
- ValueDisplay/redacted;
- SemanticRole;
- PageNumber;
- EvidenceSnippet opcional e minimizado;
- Confidence;
- ExtractionMethod.

### 7.14 ValidationFinding

- Id;
- DocumentId ou DispatchGroupId;
- RuleCode;
- Severity;
- Message;
- FieldKey;
- IsResolved;
- ResolutionType;
- ResolvedBy;
- ResolutionNote;
- timestamps.

### 7.15 DispatchGroup

- Id;
- BatchId;
- ClientId;
- EstablishmentId nullable;
- GroupingKey;
- PeriodLabel;
- Status;
- ApprovalState;
- ApprovedBy/At;
- recipients snapshot;
- subject snapshot;
- body snapshot;
- attachment hash list;
- dispatch fingerprint.

### 7.16 DeliveryAttempt

- Id;
- DispatchGroupId;
- Provider;
- SenderAccount;
- AttemptNo;
- IdempotencyKey;
- StartedAtUtc;
- CompletedAtUtc;
- Result: Pending/Accepted/Failed/Ambiguous;
- ProviderMessageId;
- HttpStatus;
- ErrorCode;
- ErrorMessageRedacted;
- ProviderRequestCorrelationId nullable.

### 7.17 AuditEvent

Append-only lógico:

- Id;
- OrganizationId;
- UserId;
- DeviceId;
- AppVersion;
- EntityType/EntityId;
- Action;
- Category;
- Severity;
- Data JSON redigido;
- TimestampUtc;
- CorrelationId.

Não permita `UPDATE` de eventos normais de auditoria. Para corrigir uma informação, adicione evento compensatório.

### 7.18 FeatureFlag

- Key;
- Value;
- Scope;
- MinimumAppVersion;
- UpdatedAtUtc;
- UpdatedBy.

Inclua `email.send.enabled` para kill switch.

---

## 8. Persistência local e sincronização

SQLite local não é a fonte de verdade dos cadastros compartilhados. Ele funciona como:

- cache;
- fila offline;
- armazenamento de estado do lote;
- preferências locais;
- índice de documentos locais;
- recuperação após crash.

PostgreSQL é a fonte de verdade compartilhada para cadastro, regras e auditoria central.

### 8.1 Sincronização

Implemente `ISyncService` com:

- pull incremental por versão/checkpoint;
- push de comandos/eventos pendentes;
- retries com backoff e jitter;
- deduplicação por operação/idempotency key;
- sincronização manual e automática;
- estado visível na UI.

### 8.2 Tempo real

SignalR informa que entidades mudaram. O cliente então busca a versão atual pela API; não dependa de payload de broadcast como única fonte de verdade.

### 8.3 Concorrência

Use optimistic concurrency. Para CNPJ, destinatário, template, status e regra de envio, conflito deve ser explícito.

### 8.4 Modo offline

Permita importação e análise local. Por padrão, bloqueie `Send` quando a auditoria central estiver indisponível. `Test` e operações locais podem continuar, registrando fila de eventos.

---

## 9. Armazenamento de documentos

### Regra inicial

PDFs reais permanecem na máquina do operador. O servidor recebe apenas metadados necessários.

### Estrutura local sugerida

```text
FolhasDaMichellyData/
  A_ENVIAR/
  PROCESSANDO/
  ENVIADOS/
    2026-08/
  ERROS/
  DUPLICADOS/
  QUARENTENA/
  CACHE/
  LOGS/
```

Não mover o arquivo original antes de registrar o estado correspondente.

### Futuro opcional

Adicionar storage de objetos criptografado se houver requisito de compartilhar os próprios PDFs entre máquinas. Isso não faz parte do MVP e exige nova análise de LGPD, retenção e chaves.

---

## 10. Entrada de arquivos e abstrações

Crie:

```csharp
public interface IDocumentExtractor
{
    bool CanHandle(DocumentInput input);
    Task<DocumentExtractionResult> ExtractAsync(DocumentInput input, CancellationToken ct);
}
```

E serviços separados:

- `IFileHasher`;
- `IMimeDetector`;
- `IPdfTextExtractor`;
- `IDocumentClassifier`;
- `IDocumentParser`;
- `IClientResolver`;
- `IValidationEngine`;
- `IGroupingEngine`.

### Extensões

PDF é obrigatório no MVP. Estruture adaptadores para DOCX/XLSX/imagem sem adicioná-los ao caminho crítico até existirem fixtures e critérios de aceite.

---

## 11. Pipeline de reconhecimento documental

Ordem obrigatória:

1. validar arquivo e caminho;
2. detectar MIME pelo conteúdo quando possível;
3. calcular SHA-256 por streaming;
4. consultar cache pelo hash;
5. extrair texto nativo;
6. detectar se é necessário OCR;
7. classificar tipo por âncoras e sinais;
8. executar parser específico;
9. extrair campos com papéis semânticos;
10. resolver cliente;
11. executar validações;
12. calcular confiança;
13. persistir evidências;
14. apresentar ao operador.

### 11.1 Não parsear apenas por regex global

Amostras demonstram vários CPFs/CNPJs no mesmo documento. A regra deve ser contextual.

Exemplos de papéis:

- `EmployerTaxId`;
- `ClientTaxId`;
- `EmployeeCpf`;
- `UnionCnpj`;
- `DocumentIssuerTaxId`;
- `EstablishmentCnpj`.

### 11.2 Score de confiança

Exemplo de composição:

- tipo documental reconhecido por âncoras fortes;
- CNPJ/CPF válido matematicamente;
- valor encontrado próximo a rótulo semântico;
- razão social coerente;
- estabelecimento conhecido;
- competência extraída;
- ausência de conflito.

Não use um número mágico como autorização única. O score apenas orienta; regras bloqueantes continuam independentes.

---

## 12. Perfis iniciais inspirados nas amostras

Crie fixtures **sintéticos** que reproduzam apenas padrões estruturais, com nomes e identificadores fictícios.

### 12.1 Férias

Âncoras possíveis:

- `DEMONSTRATIVO DE FÉRIAS`;
- `RECIBO DE FÉRIAS`;
- `Empresa`;
- `CNPJ`;
- `Empregado`;
- `CPF`;
- `Período de Gozo`.

Regra: CNPJ associado a `Empresa` é o identificador do cliente; CPF associado a `Empregado` não deve ser usado para resolver o cliente PJ.

### 12.2 FGTS Digital

Âncoras:

- `GFD - Guia do FGTS Digital`;
- `CPF/CNPJ do Empregador`;
- `Nome/Razão Social do Empregador`;
- `Competência`;
- `Pagar este documento até`;
- `Valor a recolher`.

Algumas representações podem trazer somente raiz/parte do CNPJ no texto principal. Permita resolver por CNPJ raiz apenas se o resultado for único e coerente com a razão social.

### 12.3 Folha de Pagamento

Âncoras:

- `Folha de Pagamento`;
- `Empresa:`;
- `CNPJ:`;
- `Mês/Ano:`;
- `Emissão:`.

O cabeçalho pode se repetir em várias páginas. Deduplicate campos repetidos e trate o PDF inteiro como um documento.

### 12.4 Documento de Arrecadação / INSS

Âncoras:

- `Documento de Arrecadação de Receitas Federais`;
- `CNPJ`;
- `Razão Social`;
- `Período de Apuração`;
- `Data de Vencimento`;
- `Valor Total do Documento`.

Extrair vencimento/valor para aviso e e-mail quando confiança alta.

### 12.5 13º Salário

Âncoras:

- `Recibo de Pagamento`;
- `13º Salário` ou `Adiantamento de 13º Salário`;
- `Competência`;
- `Inscrição`;
- `Empregador`;
- `Empregado`;
- `CPF`.

Amostras mostram que um mesmo arquivo pode conter estabelecimentos/filiais diferentes do mesmo grupo. Classifique cada inscrição por raiz; se todas pertencerem ao mesmo cliente raiz, o arquivo pode permanecer unido. Se houver raízes de clientes diferentes, bloqueie e peça revisão/split.

### 12.6 Pró-Labore

Âncoras:

- `RECIBO DE PRÓ-LABORE`;
- `Empresa`;
- `CNPJ`;
- `Nome`;
- `CPF`;
- texto `referente ao meu pró-labore do mês`.

O `Empresa/CNPJ` resolve o cliente; `Nome/CPF` representa recebedor/sócio.

### 12.7 Termo de Rescisão

Âncoras:

- `TERMO DE RESCISÃO DO CONTRATO DE TRABALHO`;
- `IDENTIFICAÇÃO DO EMPREGADOR`;
- `IDENTIFICAÇÃO DO TRABALHADOR`;
- `CNPJ/CEI`;
- `CPF`;
- `CNPJ e Nome da Entidade Sindical`.

Regra crítica: o CNPJ da entidade sindical é **terceiro** e não pode resolver o cliente. O CNPJ sob `IDENTIFICAÇÃO DO EMPREGADOR` é o candidato correto.

---

## 13. Validação de CNPJ e CPF

Implemente Value Objects com:

- normalização para dígitos;
- máscara apenas para display;
- algoritmo oficial de dígitos verificadores;
- rejeição de sequências inválidas;
- igualdade por valor normalizado.

Nunca use somente comprimento.

### CNPJ raiz

Exponha propriedade com os oito primeiros dígitos do CNPJ normalizado. Use raiz apenas conforme política explícita.

---

## 14. Resolução de cliente

Implemente `ClientResolutionResult` com:

- `ResolvedClientId` nullable;
- `ResolvedEstablishmentId` nullable;
- `ResolutionMethod`;
- `Confidence`;
- evidências;
- candidatos alternativos;
- blockers.

Ordem:

1. Employer/Client CNPJ completo exato;
2. Establishment CNPJ exato;
3. raiz CNPJ única + coerência de nome;
4. Client CPF exato, somente para PF e papel correto;
5. código interno exato;
6. nome legal normalizado exato;
7. alias cadastrado;
8. fuzzy suggestion apenas para operador.

### Override manual

Permita corrigir o cliente. Exija:

- usuário;
- timestamp;
- valor anterior;
- valor novo;
- motivo opcional/obrigatório conforme severidade;
- reexecução de validações;
- invalidação de aprovação anterior.

---

## 15. Competência e período

Crie tipo `DocumentPeriod` capaz de representar:

```text
Kind: Monthly | Annual | DateRange | EventDate | AssessmentPeriod | Unknown
Month/Year nullable
Year nullable
StartDate nullable
EndDate nullable
DueDate nullable
OriginalText nullable
```

Não force férias/rescisão em `MM/YYYY` se isso perder significado.

---

## 16. Motor de validação

Crie interface:

```csharp
public interface IValidationRule<T>
{
    string Code { get; }
    Task<IReadOnlyList<ValidationFinding>> EvaluateAsync(T context, CancellationToken ct);
}
```

Severidades:

- Info;
- Warning;
- Error;
- Blocker.

### 16.1 Regras universais bloqueantes

- arquivo ausente ou alterado;
- hash incompatível após aprovação;
- cliente não resolvido;
- resolução ambígua;
- cliente inativo;
- nenhum destinatário To ativo;
- e-mail inválido;
- documento de cliente diferente dentro do grupo;
- duplicidade proibida;
- send kill switch desligado;
- versão do app inferior à mínima exigida para Send;
- permissão `email.send` ausente.

### 16.2 Regras de qualidade

- campo obrigatório vazio;
- competência ausente;
- vencimento passado;
- valor zero inesperado;
- total negativo inesperado;
- documento com página aparentemente faltante;
- diferença aritmética acima de tolerância;
- variação histórica incomum;
- texto de e-mail com placeholder não resolvido.

### 16.3 Cálculos

Não recrie folha ou tributos. Apenas valide relações internas explicitamente suportadas pelo perfil. Exemplo: `TotalProventos - TotalDescontos ~= Liquido` com tolerância monetária.

### 16.4 Anomalia histórica

Se implementar, usar como Warning. Nunca bloquear baseado somente em modelo estatístico/IA.

---

## 17. Duplicidade e idempotência

### SHA-256

Calcule por streaming. Armazene em hexadecimal normalizado.

### DocumentDuplicateKey

No mínimo:

```text
OrganizationId + SHA256
```

### SemanticDuplicateKey

Configurável por tipo:

```text
ClientId + DocumentTypeId + NormalizedPeriod + ExternalDocumentId
```

### DispatchFingerprint

Calcule hash canônico de:

- ClientId;
- EstablishmentId;
- sender account;
- To/CC normalizados e ordenados;
- subject;
- body canonical;
- lista ordenada de attachment hashes.

### Janela ambígua

Se o provedor retornar timeout após a solicitação e não for possível saber se aceitou, marque `Ambiguous`. Não faça retry cego. Tente reconciliação por provider ID, rascunho, Sent Items ou estratégia documentada.

---

## 18. Agrupamento

`IGroupingEngine` deve considerar:

- ClientId;
- EstablishmentId quando relevante;
- DocumentPeriod;
- DocumentType grouping policy;
- RecipientProfile;
- opção manual.

O usuário pode `Split` e `Merge` grupos antes da aprovação. Toda alteração invalida snapshots e requer revalidação.

---

## 19. Templates de e-mail

Crie mecanismo seguro de placeholders, sem executar código arbitrário.

Placeholders mínimos:

```text
{{cliente.razao_social}}
{{cliente.nome_preferencia}}
{{cliente.nome_preferencia_ou_razao_social}}
{{contato.nome}}
{{periodo.rotulo}}
{{documentos.lista}}
{{documentos.quantidade}}
{{vencimentos.lista}}
{{operador.nome}}
{{escritorio.nome}}
```

### Fallbacks

Todo placeholder deve ter regra clara. Placeholder não resolvido gera blocker antes de enviar.

### Linguagem

Não inferir gênero. Usar texto neutro, salvo preferência explicitamente cadastrada.

### HTML

Suporte HTML simples e texto alternativo. Sanitize conteúdo configurável; não permitir scripts.

---

## 20. IA opcional

Crie abstração `IMessageAssistant` apenas depois do compositor determinístico estar pronto.

Padrão:

```text
Disabled = true
AllowedFields = PreferredName, DocumentTypeNames, PeriodLabel, DueDates
```

Regras:

- nunca enviar PDF por padrão;
- nunca enviar CPF/salário/dados de empregado;
- não escolher destinatário;
- não alterar documento;
- resultado sempre editável e revisável;
- registrar provider/model/configuração sem expor segredos;
- falha de IA volta ao template determinístico.

A implementação de IA não é requisito para MVP.

---

## 21. Modos de operação

### TEST

- destination override obrigatório;
- assunto prefixado;
- relatório mostra destinatário real simulado;
- e-mail real do cliente nunca usado.

### DRAFT

- cria rascunho real quando provider suporta;
- não envia;
- persiste provider draft ID;
- permite abrir/revisar.

### SEND

- revalidação completa;
- permissão;
- kill switch;
- versão mínima;
- confirmação humana;
- processamento sequencial ou concorrência baixa configurável;
- idempotência.

---

## 22. Interface comum de e-mail

```csharp
public interface IEmailProvider
{
    string ProviderKey { get; }
    Task<EmailAccountInfo> GetAccountAsync(CancellationToken ct);
    Task<EmailProviderCapabilities> GetCapabilitiesAsync(CancellationToken ct);
    Task<DraftResult> CreateDraftAsync(EmailEnvelope envelope, CancellationToken ct);
    Task<SendResult> SendAsync(EmailEnvelope envelope, CancellationToken ct);
    Task<ReconciliationResult> ReconcileAsync(DeliveryAttempt attempt, CancellationToken ct);
}
```

Não faça Domain/Application depender de SDK Google/Microsoft.

---

## 23. FakeEmailProvider obrigatório

Antes de qualquer API real:

- implementar `FakeEmailProvider`;
- persistir mensagens renderizadas em diretório temporário de teste;
- simular sucesso, falha transitória, falha permanente, timeout e resultado ambíguo;
- permitir latência artificial;
- registrar IDs fictícios;
- garantir que nenhum socket externo seja necessário.

Todo fluxo de UI e auditoria deve funcionar com Fake.

---

## 24. Microsoft Graph

Use Graph v1.0.

### Autenticação

- OAuth 2.0 delegado;
- MSAL;
- public client para desktop conforme arquitetura final;
- scopes mínimos;
- `Mail.Send` apenas quando necessário;
- rascunhos usam permissões adequadas e documentadas;
- nunca usar senha básica.

### Envio

- anexos;
- HTML/texto;
- salvar em Sent Items conforme comportamento da API;
- mapear `202 Accepted` para `AcceptedByProvider`, não `Delivered`.

### Conta compartilhada

Tratar como feature separada. Só implementar se permissões do tenant e cenário real forem conhecidos.

### Retry

Respeitar `Retry-After`, throttling e categorias HTTP. Não retry em 4xx permanente sem mudança de estado.

---

## 25. Gmail API

### Autenticação

- OAuth 2.0 para aplicativo instalado;
- navegador do sistema;
- loopback redirect apropriado;
- client IDs por plataforma quando exigido pela configuração Google;
- refresh token armazenado em cofre seguro;
- scopes mínimos.

### Mensagens

- MIME correto;
- UTF-8;
- anexos;
- create draft e send;
- mapear IDs retornados;
- tratar revogação e expiração.

### Publicação do OAuth

Documentar consent screen, usuários de teste, verificação de escopos e diferenças entre Workspace interno e uso externo.

---

## 26. Cofre de segredos local

Crie `ISecretStore`.

Implementações:

- Windows: Credential Manager/DPAPI ou implementação nativa aprovada;
- macOS: Keychain;
- Test: in-memory.

Não use um JSON “criptografado” com chave hard-coded no executável.

Tokens de e-mail são associados ao usuário e à máquina, salvo desenho posterior explicitamente aprovado.

---

## 27. Autenticação do próprio aplicativo

Separe **login no Folhas da Michelly** de **conexão da conta de e-mail**.

O login do app controla organização, papéis e auditoria. A conta Gmail/Outlook controla o remetente.

Na Fase 0, escolha via ADR entre:

- ASP.NET Core Identity + JWT/refresh + 2FA;
- OIDC com provedor externo adequado.

Critérios:

- baixo custo operacional;
- possibilidade de revogar usuário/dispositivo;
- MFA/2FA;
- nenhum segredo no cliente;
- boa experiência desktop.

Não implemente autenticação caseira sem bibliotecas consolidadas.

---

## 28. UI Avalonia

### Direção

- profissional;
- clara;
- identidade preto/dourado/branco com uso moderado;
- não sacrificar contraste;
- responsiva a diferentes resoluções;
- foco em teclado e mouse.

### Navegação

```text
Dashboard
Clientes
Documentos / Novo Lote
Rascunhos e Envios
Auditoria
Relatórios
Configurações
Ajuda / Sobre
```

### Grid de revisão

Colunas mínimas:

- checkbox;
- status/severidade;
- arquivos;
- tipo;
- cliente;
- estabelecimento;
- identificação;
- período;
- destinatários;
- anexos;
- avisos;
- aprovação.

### Cores

Use cor + ícone + texto. Nunca cor isolada.

### Preview

Implementar visualização de PDF de forma cross-platform com componente/licença aprovada ou abrir visualizador do sistema como fallback inicial. Não bloquear MVP por um viewer sofisticado.

---

## 29. Fluxo de aprovação

Estados sugeridos de documento:

```text
Imported
Extracting
Extracted
NeedsOcr
Classified
Unresolved
Resolved
ValidationFailed
Ready
Grouped
Approved
Processed
Archived
Error
Duplicate
Quarantined
```

Estados de grupo:

```text
Building
Blocked
ReadyForReview
Approved
DraftCreating
DraftCreated
Sending
AcceptedByProvider
Failed
Ambiguous
Reconciled
Completed
Cancelled
Incident
```

### Regras

- qualquer alteração de arquivo, cliente, recipient, template ou grupo após aprovação invalida aprovação;
- `Send` só parte de `Approved`;
- `AcceptedByProvider` não significa `Delivered`;
- movimento de arquivo ocorre após estado transacional apropriado;
- falha de movimento não deve causar reenvio.

---

## 30. Auditoria funcional

Eventos mínimos:

- login/logout;
- conexão/desconexão de e-mail;
- criação/edição/inativação de cliente;
- mudança de destinatário;
- publicação de perfil de parser;
- importação de lote;
- classificação automática/manual;
- override de cliente;
- validação;
- aprovação/revogação;
- criação de rascunho;
- tentativa de envio;
- resultado do provedor;
- movimentação de arquivo;
- exportação de relatório;
- alteração de permissão;
- update do app;
- incidente.

### Redaction

Não registrar corpo integral de folha, token ou senha. Snapshots necessários de e-mail devem ficar em armazenamento apropriado e protegido, não no log técnico textual.

---

## 31. Relatórios

### XLSX obrigatório

Crie workbook com abas:

1. `Resumo`;
2. `Itens`;
3. `Erros`;
4. `Duplicados`;
5. `Auditoria`.

### Colunas de Itens

- BatchId;
- data/hora;
- operador;
- cliente;
- estabelecimento;
- documento;
- tipo;
- período;
- hash abreviado;
- destinatário;
- status;
- severidade máxima;
- provider;
- message ID;
- erro;
- versão app.

### Estilo

- verde sucesso;
- vermelho erro/bloqueio;
- amarelo warning;
- azul draft;
- cinza cancelado/ignorado;
- filtros e freeze header;
- legenda de cores.

CSV equivalente para interoperabilidade.

PDF resumido opcional posteriormente.

---

## 32. Tratamento de erros

Crie códigos estáveis, exemplo:

```text
FILE_NOT_FOUND
FILE_CHANGED_AFTER_APPROVAL
PDF_TEXT_EMPTY
DOCUMENT_TYPE_UNKNOWN
EMPLOYER_ID_NOT_FOUND
CLIENT_NOT_FOUND
CLIENT_AMBIGUOUS
CLIENT_INACTIVE
RECIPIENT_MISSING
RECIPIENT_INVALID
DOCUMENT_CONFLICT
PERIOD_CONFLICT
DUPLICATE_EXACT
DUPLICATE_SEMANTIC
TEMPLATE_UNRESOLVED_TOKEN
AUTH_REQUIRED
AUTH_REVOKED
PROVIDER_THROTTLED
PROVIDER_REJECTED
PROVIDER_TIMEOUT
SEND_AMBIGUOUS
SYNC_OFFLINE
SYNC_CONFLICT
UPDATE_REQUIRED
SEND_DISABLED_REMOTELY
```

Mensagens para usuário devem ser amigáveis; logs internos mantêm detalhes técnicos redigidos.

---

## 33. Retry e recuperação

- I/O local: retry curto quando erro transitório;
- API: exponential backoff + jitter;
- respeitar `Retry-After`;
- não retry automático de envio após resultado ambíguo;
- persistir tentativa antes da chamada externa;
- persistir resultado assim que retornar;
- se app fechar, reconstruir estado a partir do banco;
- criar job de reconciliação para estados `Sending/Ambiguous`.

---

## 34. Atualizador e releases

Implemente atrás de `IAppUpdateService`.

### Estratégia recomendada

Velopack, sujeito a validação de compatibilidade/licença na Fase 0.

Canais separados por plataforma/arquitetura e maturidade.

Exemplo lógico:

```text
win-x64-stable
win-x64-beta
osx-arm64-stable
osx-arm64-beta
osx-x64-stable
osx-x64-beta
```

### Update feed

Não embuta token pessoal do GitHub em aplicativo instalado. O código-fonte pode permanecer privado enquanto os pacotes assinados ficam em endpoint de release somente leitura apropriado, como object storage/CDN ou repositório separado de binários.

### Processo

1. merge em main;
2. tag SemVer;
3. CI roda testes;
4. build por RID;
5. assinatura;
6. notarização macOS;
7. gerar pacote/update metadata;
8. publicar no canal beta;
9. smoke test;
10. promover stable.

### Rollback

Manter releases anteriores e procedimento de downgrade/reparação documentado.

---

## 35. Branding e assets

Nome visível: **Folhas da Michelly**.

Assets de origem serão fornecidos pelo usuário. Quando o proprietário identificar os arquivos e autorizar expressamente uso, versionamento e distribuição, eles podem ficar no projeto que os empacota; mestres e variantes de produção podem ficar em `assets/brand/source/` e `assets/brand/generated/` quando apropriado. Registre origem, finalidade e autorização.

### Não faça

- não transforme foto pessoal em ícone sem solicitação expressa do proprietário;
- não vetorize automaticamente um raster de baixa resolução e assumir qualidade profissional;
- não embuta fotos sem finalidade e autorização registradas;
- não use imagens sem registrar licença/origem.

### Faça

- usar logotipo do escritório como referência visual;
- solicitar vetor ou alta resolução para release final;
- gerar variantes `app-icon.svg/png/ico/icns` quando o mestre estiver aprovado;
- inserir metadados/alt text quando aplicável.

---

## 36. Dados de teste e fixtures

Todos os testes automatizados de domínio, documentos e integrações usam dados fictícios. Assets autorizados de identidade podem ser verificados por nome, hash e empacotamento.

Crie geradores de:

- CNPJ válido sintético;
- CPF válido sintético;
- clientes PF/PJ;
- matriz/filiais;
- documentos sintéticos por tipo;
- PDFs de teste com texto;
- casos com CNPJ sindical/terceiro;
- documentos multipágina;
- nomes semelhantes;
- competência divergente;
- duplicidade.

### Regra de amostras reais

Amostras reais podem ser usadas quando o proprietário identificar os arquivos e autorizar a finalidade. Versioná-las exige autorização nominal adicional, minimização e revisão de segurança; prefira cópias anonimizadas/redigidas antes do Git ou de ambientes cloud. Credenciais e segredos permanecem proibidos.

---

## 37. Testes de domínio

Cobrir:

- CNPJ/CPF;
- CNPJ root;
- e-mail normalization;
- client resolver;
- establishment resolution;
- period parsing;
- grouping keys;
- duplicate keys;
- dispatch fingerprint;
- state transitions;
- permission checks;
- validation severity.

Use testes parametrizados para cenários de identificadores.

---

## 38. Testes de parsers

Cada parser deve ter golden fixtures sintéticos:

- texto de entrada;
- `DocumentExtractionResult` esperado;
- campos e semantic roles;
- confidence;
- blockers.

Testes específicos:

- férias: não confundir CPF empregado com cliente;
- rescisão: não confundir CNPJ sindical;
- 13º: aceitar múltiplas filiais de mesma raiz, bloquear raízes distintas;
- folha multipágina: deduplicate header;
- FGTS: resolver raiz quando única;
- arrecadação: extrair vencimento e total;
- pró-labore: cliente empresa x pessoa recebedora.

---

## 39. Testes de aplicação

Cobrir:

- importação;
- cache;
- reprocessamento;
- override;
- aprovação individual;
- aprovação em lote;
- alteração após aprovação;
- templates;
- Test/Draft/Send com Fake;
- recuperação após reinício;
- sync queue;
- conflito de cadastro;
- kill switch;
- minimum app version.

---

## 40. Testes de infraestrutura

- EF Core SQLite;
- EF Core PostgreSQL em container/test service quando permitido;
- migrações;
- API;
- SignalR;
- filesystem;
- secret store abstractions;
- report generator;
- update metadata parser.

Não exija conta real Google/Microsoft na suíte padrão.

---

## 41. Testes de provedores reais

Criar suite marcada/opt-in:

- conta de teste dedicada;
- destinatário controlado;
- não usar clientes reais;
- executar manualmente ou em pipeline protegido;
- validar draft e send;
- validar anexos;
- validar revogação de OAuth;
- validar throttling simulado via mocks.

---

## 42. Testes de UI

Use testes headless Avalonia quando viáveis para:

- navegação;
- filtros;
- seleção em lote;
- bloqueios;
- confirmação;
- acessibilidade básica;
- renderização de estados.

Faça smoke tests manuais em Windows e macOS antes de stable.

---

## 43. CI

Pipeline em PR:

```text
restore
format/analyzers
build
unit tests
integration tests sem secrets
publish dry-run Windows
publish dry-run macOS
security/license checks
```

Use matriz de runners onde necessário.

### Cache

Cache NuGet seguro; não cachear credenciais.

### Dependabot/Renovate

Pode ser usado para dependências, mas não auto-merge de atualização crítica sem testes.

---

## 44. Release CI/CD

Releases somente por tag ou workflow manual autorizado.

Segredos de assinatura ficam em GitHub Actions Secrets/Environment secrets ou cofre externo, nunca no repo.

Ambientes:

- `development`;
- `staging`;
- `production`.

Proteja `production` com aprovação humana.

---

## 45. Backend deployment

Containerize o servidor.

Componentes:

- ASP.NET Core API;
- PostgreSQL gerenciado;
- TLS;
- logs estruturados;
- backup automatizado;
- health checks;
- migração controlada.

Não escolha provedor cloud apenas por conveniência do Codex. Documente custo, região, backups, observabilidade e possibilidade de migração. Para produção brasileira, considerar residência/região e LGPD na decisão.

---

## 46. Segurança do backend

- authentication/authorization em cada endpoint;
- OrganizationId derivado do contexto autenticado, não confiado do cliente;
- rate limit;
- validação de DTOs;
- anti-CSRF onde aplicável;
- headers seguros;
- CORS mínimo;
- secrets via environment/key vault;
- migrations com least privilege;
- logs redigidos;
- audit append-only;
- backups;
- restore testado;
- dependency scanning.

---

## 47. Segurança do desktop

- cofre seguro;
- dados em diretório de app apropriado, não pasta do executável;
- não aceitar update não confiável;
- arquivos temporários com nomes imprevisíveis;
- validar extensão/MIME;
- limitar tamanho e páginas configuravelmente;
- não executar macros ou conteúdo ativo de documentos;
- sanitizar HTML do template;
- evitar path traversal;
- não abrir anexos automaticamente.

---

## 48. LGPD e minimização

- só sincronizar campos necessários;
- não enviar conteúdo de PDFs ao backend no MVP;
- registrar finalidade operacional em documentação;
- política de retenção configurável;
- acesso por papel;
- exportação de auditoria;
- procedimento de incidente;
- não expor CPF integral em lista quando últimos dígitos bastarem;
- não usar dados reais em analytics/telemetria.

---

## 49. Fluxo de incidente

Crie entidade/feature para registrar incidentes de envio.

Não prometa recall universal de e-mail.

Campos:

- affected DeliveryAttempt;
- detectedAt;
- detectedBy;
- category;
- severity;
- description;
- remediation;
- status;
- closedBy/At.

---

## 50. Critérios de aceite obrigatórios

### Cenário A - cadastro sincronizado

1. Admin cadastra Cliente X no Mac.
2. Servidor salva.
3. Windows conectado recebe notificação.
4. Cliente aparece sem reiniciar app.
5. Auditoria contém criação.

### Cenário B - férias com empregado

1. PDF sintético contém CNPJ empregador e CPF empregado.
2. Parser escolhe EmployerTaxId.
3. CPF empregado não gera candidato de cliente PJ.
4. item fica Ready.

### Cenário C - rescisão com sindicato

1. PDF contém CNPJ empregador e CNPJ sindicato.
2. parser rotula ambos.
3. ClientResolver usa somente EmployerTaxId.
4. teste falha se sindicato for escolhido.

### Cenário D - filiais

1. PDF contém CNPJs de duas filiais da mesma raiz.
2. ambas pertencem a Cliente X.
3. documento resolve Cliente X e registra estabelecimentos.
4. política decide agrupamento.

### Cenário E - duas raízes

1. arquivo possui empregadores de raízes diferentes.
2. sistema bloqueia.
3. operador precisa separar/corrigir.

### Cenário F - cliente inativo

Documento corresponde exatamente a cliente inativo. Resultado: Blocker.

### Cenário G - lote

Itens verdes podem ser selecionados em massa; bloqueados não entram.

### Cenário H - alteração após aprovação

Alterar destinatário invalida aprovação e exige nova confirmação.

### Cenário I - duplicidade

Reimportar mesmo SHA-256 gera Duplicate e não cria novo envio.

### Cenário J - falha ambígua do provedor

Timeout após request gera Ambiguous; sistema não retry cego.

### Cenário K - atualização obrigatória

Servidor define versão mínima maior. Send fica bloqueado; Test/visualização continuam conforme política.

### Cenário L - kill switch

`email.send.enabled=false` impede Send em todas as máquinas rapidamente.

### Cenário M - modo teste

Nenhum endereço real recebe mensagem; relatório preserva destinatário simulado.

---

## 51. Definição de pronto para MVP

- builds Windows e macOS;
- login/RBAC;
- sync de clientes;
- cadastro PF/PJ/filial;
- PDF digital;
- tipos iniciais de documentos;
- reconhecimento semântico;
- validações;
- individual + lote;
- templates;
- FakeEmailProvider;
- Graph;
- Gmail;
- Test/Draft/Send;
- auditoria;
- XLSX/CSV;
- updater beta/stable;
- documentação;
- piloto aprovado.

---

## 52. Anti-padrões proibidos

- WPF no novo desktop;
- Electron/Tauri introduzido sem ADR e prova de necessidade;
- “salvar senha do Gmail” em banco;
- SMTP com senha como caminho principal;
- automação de navegador/webmail;
- usar primeiro CNPJ encontrado;
- usar CPF de empregado para resolver cliente PJ;
- fuzzy match silencioso;
- IA como roteador;
- `catch(Exception){}` silencioso;
- retry infinito;
- `async void` fora de event handler;
- bloquear UI thread com I/O;
- usar arquivo de configuração como banco central;
- guardar documentos reais no Git;
- copiar secrets em logs;
- release manual sem versionamento;
- update não assinado em produção;
- banco central acessado diretamente pelo desktop sem API;
- `OrganizationId` fornecido pelo cliente sem validação de autorização;
- hard delete de auditoria;
- mover arquivo e depois tentar descobrir se enviou.

---

## 53. Documentação obrigatória

### README.md

- objetivo;
- pré-requisitos;
- run desktop;
- run server;
- testes;
- configuração de desenvolvimento.

### ARCHITECTURE.md

- componentes;
- boundaries;
- diagramas;
- cross-platform.

### DATA_MODEL.md

- entidades;
- índices;
- retenção;
- sync.

### DOCUMENT_RECOGNITION.md

- pipeline;
- semantic roles;
- perfis;
- fixtures;
- como adicionar novo tipo.

### EMAIL_PROVIDERS.md

- OAuth;
- scopes;
- drafts/send;
- erros;
- teste.

### SECURITY.md

- threat model;
- secret stores;
- LGPD;
- update security;
- incidentes.

### RELEASES.md

- SemVer;
- canais;
- assinatura;
- CI/CD;
- rollback.

### OPERATIONS.md

- backups;
- health checks;
- suporte;
- logs;
- migração.

### TEST_PLAN.md

- matriz de testes;
- fixtures;
- provedor real opt-in;
- smoke Windows/macOS.

---

## 54. AGENTS.md que deve ser criado

Mantenha abaixo de 32 KiB e realmente útil.

Conteúdo recomendado:

```markdown
# AGENTS.md

## Projeto
Folhas da Michelly é um app desktop Avalonia + backend ASP.NET Core para reconhecer documentos contábeis e preparar/enviar e-mails com auditoria.

## Regras
- Leia docs/specs/MEGA_PROMPT_FOLHAS_DA_MICHELLY_CODEX.md e BLUEPRINT antes de mudanças arquiteturais.
- Nunca use dados operacionais/de clientes reais nem credenciais em testes; assets autorizados de identidade podem ter integridade e empacotamento verificados.
- Nunca envie e-mail real sem solicitação explícita de uma fase de integração controlada.
- Preserve compatibilidade Windows + macOS.
- Execute build e testes após alterações.
- Registre decisões arquiteturais em docs/adr.
- Não adicione dependência de produção sem verificar licença/manutenção.

## Comandos
Preencher após scaffold com restore, build, test, run desktop e run server.

## Definition of Done
Build limpo, testes pertinentes passando, documentação atualizada, nenhuma credencial versionada e nenhum arquivo real sem autorização e finalidade registradas.
```

---

## 55. Plano de execução obrigatório

### Fase 0 - Descoberta e planejamento

Objetivos:

- ler specs;
- inspecionar ambiente/repo;
- confirmar SDK;
- pesquisar versões estáveis apenas em fontes oficiais;
- validar Avalonia cross-platform;
- validar biblioteca PDF;
- validar update framework;
- criar ADRs iniciais;
- criar plan.

Entregáveis:

- `AGENTS.md`;
- `docs/EXECUTION_PLAN.md`;
- `docs/PROGRESS.md`;
- ADR-001 UI cross-platform;
- ADR-002 local+cloud data;
- ADR-003 auth;
- ADR-004 updater;
- ADR-005 PDF extraction.

Não criar Graph/Gmail nem enviar mensagens.

### Fase 1 - Scaffold e CI

- solução;
- projetos;
- Avalonia app abrindo no Mac;
- server;
- test projects;
- GitHub Actions Windows/macOS;
- analyzers;
- global.json;
- packages centralizados.

Gate: build/test em runners suportados.

### Fase 2 - Backend, auth e sync skeleton

- PostgreSQL;
- Organization/User/RBAC;
- API;
- SQLite cache;
- sync protocol;
- SignalR;
- mock auth/local dev.

Gate: dois clientes simulados sincronizam via tests.

### Fase 3 - Cadastro central

- Client;
- identifiers;
- establishments;
- recipients;
- templates;
- ativo/inativo;
- CRUD UI;
- import/export.

Gate: edição concorrente e inativação testadas.

### Fase 4 - Ingestão e reconhecimento

- file picker/drag-drop;
- PDF text;
- hash;
- classifier;
- parser profiles;
- tipos iniciais;
- semantic fields;
- client resolver;
- fixtures sintéticos.

Gate: golden parser tests.

### Fase 5 - Validação e agrupamento

- rules;
- findings;
- duplicate;
- periods;
- grouping;
- overrides;
- snapshots.

Gate: blockers não podem aprovar.

### Fase 6 - Workflow UI + FakeEmailProvider

- individual;
- lote;
- Test/Draft/Send simulados;
- audit;
- XLSX/CSV;
- recovery.

Gate: fluxo end-to-end sem internet.

### Fase 7 - Microsoft Graph

- registro app de teste;
- OAuth;
- secure token;
- drafts;
- send;
- attachments;
- failure mapping.

Gate: somente conta de teste.

### Fase 8 - Gmail

- OAuth desktop;
- secure token;
- drafts;
- send;
- attachments;
- consent docs.

Gate: somente conta de teste.

### Fase 9 - Hardening e incidentes

- auth hardening;
- rate limits;
- redaction;
- incident workflow;
- backups;
- accessibility;
- performance.

### Fase 10 - Update e packaging

- Velopack/equivalente;
- Windows package;
- macOS package;
- signing/notarization docs;
- beta/stable;
- version minimum/kill switch.

### Fase 11 - Piloto

- configurar ambiente staging;
- importar cadastros fictícios/anonimizados;
- usar Draft/Test;
- validar em Windows real;
- correções.

### Fase 12 - Produção gradual

- liberar Send para papéis autorizados;
- monitorar falhas;
- backup restore drill;
- release stable.

---

## 56. Portões de segurança entre fases

- não avançar para API real com Fake incompleto;
- não avançar para Send real sem duplicidade/idempotência;
- não avançar para lote real sem bloqueio de ambiguidade;
- não avançar para produção sem Windows smoke test;
- não avançar para atualização automática sem assinatura/verificação;
- não avançar para cloud data real sem backup/restore e RBAC.

---

## 57. Primeira tarefa a executar AGORA

Ao receber este arquivo no repositório:

1. leia este Mega Prompt e o Blueprint integralmente;
2. execute `git status` e inventarie arquivos;
3. identifique SDK e ferramentas disponíveis;
4. não modifique código ainda se o repositório estiver vazio além das specs;
5. produza `docs/EXECUTION_PLAN.md` com as fases acima adaptadas ao ambiente;
6. crie os cinco ADRs iniciais em estado Proposed;
7. crie `AGENTS.md` curto;
8. proponha a estrutura exata da solução;
9. liste dependências candidatas, licença e motivo;
10. valide que o desenho atende Mac + Windows;
11. apresente o plano e aguarde a próxima instrução antes de implementar Fase 1, salvo se o trigger recebido autorizar expressamente continuar.

Não conecte Gmail/Outlook. Não use PDFs reais. Não publique nada.

---

## 58. Prompts de continuação

### Continuar Fase 1

```text
Com base no plano e ADRs aprovados, execute somente a Fase 1. Crie o scaffold cross-platform, CI inicial e testes mínimos. Não implemente e-mail nem parser real. Ao final, rode todos os gates e atualize PROGRESS.md.
```

### Continuar Fase 2

```text
Execute somente a Fase 2: backend, autenticação/skeleton e sincronização. Use dados sintéticos. Não processe PDFs reais. Demonstre sync em testes e documente decisões.
```

### Continuar Fase 4

```text
Execute a Fase 4 usando exclusivamente fixtures sintéticos inspirados nos perfis descritos. Priorize semantic roles, cliente/estabelecimento e golden tests. Nenhum arquivo real deve ser commitado.
```

### Auditoria de segurança

```text
Faça uma revisão adversarial do projeto contra envio ao cliente errado, vazamento de token, mistura entre organizações, retry duplicado, update malicioso, path traversal e logs com PII. Não altere comportamento antes de apresentar findings priorizados.
```

### Preparar piloto

```text
Prepare o piloto supervisionado. Mantenha Send desabilitado por padrão, valide Test e Draft, gere checklist de uma estação Windows e uma macOS, e documente rollback. Não libere produção automaticamente.
```

---

## 59. Referências técnicas oficiais que podem ser consultadas

Na implementação, priorize documentação oficial atual:

- .NET support policy: https://dotnet.microsoft.com/en-us/platform/support/policy
- Avalonia supported platforms: https://docs.avaloniaui.net/docs/supported-platforms
- Avalonia Windows: https://docs.avaloniaui.net/docs/platform-specific-guides/windows
- Avalonia macOS: https://docs.avaloniaui.net/docs/platform-specific-guides/macos
- Microsoft Graph sendMail: https://learn.microsoft.com/en-us/graph/api/user-sendmail?view=graph-rest-1.0
- Microsoft Graph delegated auth: https://learn.microsoft.com/en-us/graph/auth-v2-user
- Gmail API OAuth desktop: https://developers.google.com/identity/protocols/oauth2/native-app
- Gmail API auth/scopes: https://developers.google.com/workspace/gmail/api/auth/scopes
- GitHub hosted runners: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- Velopack: https://docs.velopack.io/
- Codex CLI: https://developers.openai.com/codex/cli/
- Codex cloud: https://developers.openai.com/codex/cloud/
- AGENTS.md: https://developers.openai.com/codex/guides/agents-md/

Se uma fonte mudou, use a documentação oficial vigente e registre a diferença em ADR/PROGRESS.

---

## 60. Observação final ao Codex

Não transforme este projeto em um protótipo descartável. Desenvolva a base como um produto real de escritório: fluxo simples para o usuário, controles rigorosos nos bastidores, logs úteis, testes reproduzíveis, atualizações seguras e capacidade de evolução.

Quando houver tensão entre “automatizar mais” e “reduzir risco de envio incorreto”, escolha a segunda opção e deixe a automação avançada atrás de configuração e revisão.

# FIM DO PROMPT MESTRE
