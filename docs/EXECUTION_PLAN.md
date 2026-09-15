# Plano de execução — Folhas da Michelly

- Versão: 1.0
- Data: 2026-08-21
- Fase autorizada: Fase 12 implementada em 2026-08-21 em modo fail-closed; abertura operacional condicionada ao aceite formal do piloto e aos gates externos
- Fontes normativas: `docs/specs/MEGA_PROMPT_FOLHAS_DA_MICHELLY_CODEX.md` e Blueprint

## 1. Objetivo e invariantes

Construir um produto desktop + cloud que processe documentos contábeis localmente, resolva cliente/estabelecimento com evidência semântica, permita revisão individual e em lote e, somente em fases controladas, use Gmail/Microsoft Graph por OAuth.

Invariantes que atravessam todas as fases:

1. zero mistura silenciosa entre clientes ou organizações;
2. testes usam dados e PDFs sintéticos; arquivos fornecidos pelo proprietário só entram no Git com autorização e finalidade registradas, enquanto credenciais, tokens e segredos nunca são versionados;
3. bloqueios nunca entram em “Aprovar todos”;
4. alteração após aprovação invalida a aprovação;
5. SHA-256, idempotência, auditoria e recuperação são requisitos de primeira classe;
6. nenhuma senha Gmail/Outlook é armazenada e nenhuma automação de webmail é usada;
7. `FakeEmailProvider` conclui o fluxo antes de APIs reais;
8. Windows e macOS são validados por build/testes reais, não apenas por compilação cruzada.

## 2. Reconciliação das especificações

Não foi encontrado conflito funcional material entre Mega Prompt e Blueprint. Há uma diferença de sequenciamento: o Blueprint agrupa relatórios na Fase 9, enquanto o Mega Prompt normativo exige XLSX/CSV junto ao fluxo end-to-end da Fase 6. Este plano segue o Mega Prompt e mantém hardening/incidentes na Fase 9. A funcionalidade final não muda; por isso não foi necessário um ADR adicional.

## 3. Fases, dependências e gates

| Fase | Escopo | Dependências | Gate/critério de aceite |
|---|---|---|---|
| 0 — Descoberta e planejamento | ambiente, specs, plano, progresso e ADRs | nenhuma | artefatos revisados; nenhuma implementação iniciada |
| 1 — Scaffold e CI | solução, projetos, Avalonia mínimo, Server mínimo, analyzers, central packages, CI | SDK .NET 10 arm64; repositório privado/Actions para validar CI | restore/build/test; app abre no Mac; dry-run `win-x64` e `osx-arm64`; matriz Windows/macOS verde |
| 2 — Backend, identidade e sync skeleton | Organization/User/RBAC, API, PostgreSQL, SQLite cache, OIDC/PKCE, fila e SignalR | F1; ADR-0002/0003 aceitos após PoC | dois clientes simulados sincronizam; conflito e revogação testados; isolamento por organização |
| 3 — Cadastro central | PF/PJ, identificadores, estabelecimentos, destinatários, templates, ativo/inativo, CRUD | F2 | concorrência crítica explícita; cliente inativo bloqueia; auditoria de alteração |
| 4 — Ingestão e reconhecimento | hash, MIME, PdfPig atrás de abstração, classificação, perfis, semantic roles, resolução | F3; ADR-0005 aprovado pelo PoC | golden tests dos sete perfis; sindicato/empregado nunca resolvem cliente incorreto |
| 5 — Validação e agrupamento | findings, períodos, duplicidade, grouping, split/merge, snapshots e override | F4 | blockers não aprovam; alteração invalida aprovação; duplicidade não gera despacho |
| 6 — Workflow UI + FakeEmailProvider | individual, lote, Test/Draft/Send simulados, auditoria, XLSX/CSV, crash recovery | F5 | fluxo end-to-end sem rede/socket; cenários success/failure/ambiguous; relatório consistente |
| 7 — Microsoft Graph | OAuth delegado, cofre, draft, send, anexos, retry/reconciliação | F6 e conta de teste dedicada | somente destinatário controlado; `202` vira Accepted, não Delivered; revogação testada |
| 8 — Gmail API | OAuth installed app, MIME, draft/send, anexos e revogação | F6 e conta de teste dedicada | somente destinatário controlado; scopes mínimos e consentimento documentados |
| 9 — Hardening e incidentes | threat model, rate limit, redaction, incidentes, backup/restore, acessibilidade/performance | F2–F8 conforme componente | findings críticos/altos resolvidos; restore testado; logs sem PII/token |
| 10 — Update e packaging | Velopack PoC, canais, assinatura, notarização, rollback | F1 estável; certificados/contas de assinatura | update assinado Windows/macOS, pacote adulterado rejeitado, rollback ensaiado |
| 11 — Piloto supervisionado | staging, dados fictícios/anonimizados, Test/Draft, estações reais | F1–F10 e aprovação operacional | checklist Windows/macOS, sem Send por padrão, rollback e métricas validados |
| 12 — Produção gradual | liberar Send por papel, monitorar, drill de backup, stable | piloto aprovado e autorização humana | kill switch, versão mínima, suporte e resposta a incidente operacionais |

## 4. Portões de segurança entre fases

- Nenhum Graph/Gmail antes do fluxo Fake completo.
- Nenhum Send real antes de duplicidade, fingerprint, tentativa persistida e estado `Ambiguous` sem retry cego.
- Nenhum lote real antes de seleção excluir blockers por construção.
- Nenhum dado real no backend antes de RBAC, isolamento por organização, backup e restore testado.
- Nenhuma stable Windows sem smoke test em Windows real.
- Nenhuma atualização automática de produção sem assinatura/verificação em ambas as plataformas.
- Nenhuma release quando houver finding crítico/alto de mistura de cliente, autorização, token, idempotência ou update.

## 5. Estrutura final proposta

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
│   ├── FolhasDaMichelly.Contracts/
│   ├── FolhasDaMichelly.Application/
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
│           ├── Pdf/
│           ├── Text/
│           └── Expected/
├── tools/
├── AGENTS.md
├── Directory.Build.props
├── Directory.Packages.props
├── global.json
├── .editorconfig
├── .gitignore
├── README.md
└── FolhasDaMichelly.slnx
```

Integrações específicas de sistema ficam inicialmente em `Infrastructure/Platform/Windows` e `Infrastructure/Platform/MacOS`, atrás de interfaces. Só serão separadas em projetos próprios se o PoC demonstrar necessidade; isso evita complexidade prematura sem espalhar condicionais.

## 6. Dependências entre projetos

| Projeto | Pode depender de | Não pode depender de |
|---|---|---|
| Domain | BCL | Application, Contracts, Infrastructure, Desktop, Server |
| Contracts | BCL; tipos primitivos compartilháveis | Infrastructure, Desktop, Server |
| Application | Domain, Contracts | Avalonia, EF Core, SDK Google/Microsoft |
| Infrastructure | Application, Domain, Contracts | Desktop |
| Desktop | Application, Contracts; Infrastructure apenas no composition root | Server; acesso direto a PostgreSQL |
| Server | Application, Contracts; Infrastructure no composition root | Avalonia/Desktop |

Testes referenciam apenas a unidade sob teste e suas dependências necessárias. SDKs de Gmail/Graph ficam confinados em adaptadores de Infrastructure.

## 7. Bibliotecas candidatas — snapshot de 2026-08-20

Versões abaixo são as últimas estáveis observadas no feed oficial NuGet nesta data. Nenhuma foi instalada na Fase 0. A Fase 1 deve fixar versões centralmente e gerar lockfiles; upgrades críticos não recebem auto-merge.

| Candidata | Versão estável | Licença | Sinal de manutenção | Uso/decisão |
|---|---:|---|---|---|
| Avalonia | 12.1.1 | MIT | release 2026-07-29; docs v12 ativas | UI cross-platform; candidata aprovada por ADR-0001/PoC |
| CommunityToolkit.Mvvm | 8.4.2 | MIT | release 2026-03-25; Microsoft Community Toolkit | MVVM, comandos e observable state |
| EF Core / SQLite | 10.0.11 | MIT | patch 2026-08-11 | ORM central e cache local |
| Npgsql EF Core | 10.0.3 | PostgreSQL | release 2026-07-10 | provider PostgreSQL |
| ASP.NET Core SignalR Client | 10.0.11 | MIT | patch .NET 2026-08-11 | notificações de sincronização |
| OpenIddict | 7.6.0 | Apache-2.0 | stable 7.6 em 2026-07; v8 apenas preview | OIDC/OAuth do app; ADR-0003/PoC |
| PdfPig | 0.1.15 | Apache-2.0 | release 2026-06-25; builds macOS | extração PDF; pré-1.0 exige pin/PoC |
| Velopack | 1.2.0 | MIT | stable 2026-06-03; projeto ativo | updater/packaging; ADR-0004/PoC |
| MimeKit | 4.17.0 | MIT | release 2026-05-26 | instalada na Fase 8, confinada ao adaptador Gmail para MIME UTF-8/anexos |
| ClosedXML | 0.105.1 | MIT | release 2026-07-25; versão estável mais recente verificada em 2026-08-21 | instalada na Fase 6 apenas em Infrastructure para XLSX; CSV permanece implementação própria |
| Microsoft.Identity.Client | 4.88.0 | MIT | release corrente 2026-08 | OAuth Microsoft delegado |
| Microsoft.Graph | 6.5.0 | MIT no repositório oficial | release 2026-08-06 | avaliado; não instalado na Fase 7, pois o subconjunto REST v1.0 ficou menor e testável via `HttpClient` |
| Google.Apis.Gmail.v1 | 1.75.0.4225 | Apache-2.0 | release 2026-08-06 | avaliado; não instalado na Fase 8, pois REST v1 + `HttpClient` cobre o subconjunto com cofre/retry próprios |
| xUnit v3 | 4.0.0 | Apache-2.0 | release 2026-08-15 | suíte de testes |
| Serilog.Extensions.Logging | 10.0.0 | Apache-2.0 | compatível com .NET 10 | logs estruturados; sinks só após revisão de redaction/licença |

Dependências deliberadamente não escolhidas nesta fase:

- Microsoft.Graph SDK: o adaptador F7 usa poucos endpoints v1.0 e `HttpClient`, evitando uma segunda dependência de produção sem perder tipagem nos contratos próprios;
- Google.Apis.Gmail.v1 SDK: o adaptador F8 usa poucos endpoints e OAuth/cofre próprios; REST v1 deixa persistência, redaction e ambiguidade explicitamente testáveis;
- iText/MuPDF: AGPL ou licença comercial; exigem aprovação específica.
- QuestPDF: modelo de licença requer avaliação de elegibilidade e não é necessário ao MVP inicial.
- FluentAssertions: mudança de licença torna desnecessário o risco; usar asserts do framework/test helpers próprios.
- MediatR: não é necessário para os boundaries propostos; evitar dependência/licença sem benefício provado.
- previews/nightlies: proibidos para produção.

Fontes: metadata oficial NuGet e repositórios oficiais ligados pelos pacotes; links arquiteturais constam nos ADRs.

## 8. Primeiro conjunto de fixtures sintéticos

Todos os PDFs terão marca visível “DADOS SINTÉTICOS — SEM VALIDADE”, nomes fictícios, e-mails em `example.invalid` e identificadores gerados por helper determinístico com dígitos verificadores. Nenhum layout real será copiado pixel a pixel.

| Perfil | Fixture positiva | Fixture adversarial/blocker | Resultado esperado principal |
|---|---|---|---|
| Férias | empregador PJ + empregado/CPF + período de gozo | CPF do empregado sem CNPJ empregador | usar `EmployerTaxId`; nunca resolver PJ pelo empregado |
| FGTS Digital | CNPJ completo, competência, vencimento e valor | somente raiz compartilhada por dois clientes | raiz única + nome pode resolver; raiz ambígua bloqueia |
| Folha de Pagamento | PDF de 3 páginas com cabeçalho repetido | página ausente/competência divergente | deduplicar campos e tratar arquivo como unidade |
| DARF/INSS | razão social, CNPJ, apuração, vencimento e total | vencido/valor zero inesperado | extrair período/valor e produzir warning/regra configurada |
| 13º Salário | duas filiais da mesma raiz | duas raízes de clientes distintos no mesmo PDF | mesma raiz permanece unida; raízes distintas bloqueiam/split |
| Pró-Labore | empresa/CNPJ + sócio/CPF | inversão de proximidade entre rótulos | empresa resolve cliente; recebedor não vira cliente PJ |
| Rescisão | empregador/CNPJ + trabalhador/CPF + sindicato/CNPJ | sindicato aparece antes do empregador | rotular todos; somente empregador pode resolver cliente |

Fixtures universais adicionais:

- PDF vazio, corrompido, criptografado, MIME divergente e acima do limite;
- nomes muito semelhantes e alias explícito;
- cliente inativo e destinatário ausente/inválido;
- duplicidade SHA-256 e duplicidade semântica;
- arquivo alterado após aprovação;
- lote misto com elegíveis e blockers;
- timeout ambíguo do Fake provider e reinício durante processamento.

Cada fixture terá:

```text
Pdf/<caso>.pdf
Text/<caso>.txt
Expected/<caso>.json
```

O JSON esperado inclui tipo, campos, semantic roles, página, confiança, candidatos e findings. A geração deve ser reproduzível por seed e não depender de rede.

## 9. Estratégia de testes por plataforma

### macOS local (Apple Silicon)

- restore/build/test de Domain, Application, Infrastructure, Server e UI headless;
- execução do Desktop e smoke de navegação/importação com fixtures sintéticos;
- SQLite, filesystem, Keychain adapter em teste controlado e publicação `osx-arm64` dry-run;
- performance básica, cancelamento e recuperação após crash simulado;
- a partir da Fase 10, assinatura/notarização somente após Xcode completo e credenciais de desenvolvimento estarem disponíveis.

### Windows obrigatório em CI e máquina real

- build/test em `windows-2025` ou label estável equivalente, com `win-x64` publish dry-run;
- testes do Credential Manager/DPAPI, path handling, file locking, diálogos e atualização;
- smoke manual em Windows 11 24H2 e, enquanto suportado pelo produto, Windows 10 22H2;
- instalador, assinatura, antivírus/SmartScreen e rollback em estação real antes de stable.

### macOS obrigatório em CI

- build/test e publish `osx-arm64` em runner macOS arm64;
- `osx-x64` apenas quando confirmado como requisito;
- assinatura/notarização em workflow protegido, nunca em PR não confiável.

### Linux CI

- testes de domínio/backend e integração PostgreSQL com service/container quando apropriado;
- Linux não substitui gates Desktop Windows/macOS.

## 10. Riscos abertos e mitigação planejada

| ID | Risco | Severidade | Mitigação/gate |
|---|---|---:|---|
| R-001 | SDK .NET ausente no Mac | Alta para F1 | instalar SDK 10.0.400 arm64 antes do scaffold |
| R-002 | repositório remoto/GitHub Actions ainda inexistente | Alta para validar CI | usuário indicar/criar repositório privado e habilitar Actions |
| R-003 | Windows 10 é Tier 2 no Avalonia atual | Média/Alta | smoke dedicado ou revisar mínimo suportado |
| R-004 | PdfPig está abaixo de 1.0 | Média | abstração, pin, PoC, fixtures adversariais e revisão manual de upgrades |
| R-005 | Xcode completo e certificados ausentes | Alta para F10, não para F1 | preparar somente antes de packaging/signing |
| R-006 | callback OIDC em Avalonia/macOS/Windows | Alta para F2 | PoC Authorization Code + PKCE com navegador do sistema |
| R-007 | token/PII em logs e filas offline | Crítica | redaction tests, secret store e revisão adversarial |
| R-008 | retry ambíguo duplicar envio | Crítica | persistir tentativa antes da chamada, fingerprint e reconciliação sem retry cego |
| R-009 | regra semântica usar empregado/sindicato | Crítica | semantic roles + golden tests obrigatórios |
| R-010 | custo/limites dos runners macOS em repo privado | Média | medir na F1; separar jobs e caches seguros |
| R-011 | `gmail.compose` é escopo restrito e exige verificação para distribuição externa | Alta para produção, não para suíte offline | projeto/conta dedicados, usuários de teste, consent screen/política/domínio e verificação Google antes do piloto |

## 11. Dependências externas futuras, não bloqueantes para Fase 1

- domínio/URL e ambiente de backend;
- provedor PostgreSQL gerenciado e região;
- contas de teste Microsoft 365 e Google;
- Apple Developer ID e certificado de assinatura Windows;
- endpoint de feed somente leitura;
- vetor/alta resolução do logotipo;
- política formal de retenção/LGPD do escritório.

## 12. Gate final e abertura operacional

A Fase 12 adiciona a preparação fail-closed da produção gradual: papel privilegiado + MFA, limites por lote/dia, autorização central idempotente, versão mínima, kill switches, painel somente leitura e runbook. A implementação não supre o aceite formal do piloto nem declara infraestrutura externa como testada. Staging HTTPS, beta/stable assinadas, certificados Windows/Apple, Xcode completo, feed, backup/restore gerenciado, monitoramento, suporte e smokes físicos macOS/Windows continuam obrigatórios. Até que as evidências sejam concluídas e uma abertura humana separada seja registrada, `Stage=Closed`, todos os switches de Send permanecem falsos e nenhum Send real pode ocorrer. Consulte `docs/PRODUCTION.md` e ADR-0009.
