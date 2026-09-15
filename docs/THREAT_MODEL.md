# Modelo de ameaças — baseline da Fase 12

## Escopo e ativos

O escopo cobre o aplicativo Avalonia, a API ASP.NET Core, o cache SQLite local, o catálogo de clientes, metadados dos documentos, tentativas de entrega, auditoria, cópias exportadas e tokens mantidos pelo armazenamento seguro do sistema operacional.

Ativos prioritários:

1. associação correta entre cliente, competência, documento e destinatário;
2. PDFs contábeis e identificadores fiscais;
3. tokens de Gmail/Outlook e sessões de dispositivo;
4. histórico de decisões, aprovações, tentativas e incidentes;
5. disponibilidade da API e possibilidade de recuperação.

## Fronteiras de confiança

- Operador local ↔ aplicativo desktop;
- aplicativo ↔ arquivos e pastas escolhidos pelo operador;
- aplicativo ↔ armazenamento seguro do sistema operacional;
- aplicativo ↔ API por HTTPS/OIDC;
- API ↔ PostgreSQL;
- API ↔ provedores de e-mail, ainda desativados por padrão;
- cópia exportada ↔ mídia externa controlada pelo escritório.

## Ameaças e controles

| Ameaça | Impacto | Controles da Fase 9 | Risco residual |
|---|---|---|---|
| Tentativas repetidas de login ou exaustão da API | indisponibilidade ou acesso indevido | bloqueio do Identity, limites particionados, filas desativadas, `Retry-After`, limites por custo | distribuidores múltiplos exigem proxy/WAF |
| Vazamento por logs e erros | exposição de CPF, CNPJ, e-mail ou token | respostas genéricas, correlação opaca, redação de texto livre, proibição de corpo/consulta em logs | bibliotecas externas devem manter configuração equivalente |
| Catálogo copiado ou adulterado | exposição ou restauração maliciosa | AES-256-GCM, PBKDF2-SHA-256, limites de tamanho, versão autenticada e prévia antes da aplicação | senha fraca ou guardada junto do arquivo reduz proteção |
| PDF/cache local copiado | exposição de documentos | diretório privado do usuário, recomendação de FileVault/BitLocker, seleção explícita de acervo | SQLite e pastas escolhidas não recebem criptografia no nível da aplicação |
| Documento/destinatário incorreto | comunicação contábil indevida | revisão humana, invalidação após alteração, bloqueio de ambiguidade, prevenção de repetição, fluxo de incidente | erro humano ainda é possível após confirmação |
| Resultado incerto do provedor | duplicidade | idempotência, reconciliação antes de repetir e incidente associado à tentativa | indisponibilidade prolongada do provedor requer decisão humana |
| Alteração ou exclusão do histórico | perda de evidência | eventos append-only, incidentes sem exclusão e transições versionadas | administrador com acesso ao banco/volume ainda pode adulterar o armazenamento |
| Credencial inserida no repositório/arquivo | comprometimento de conta | armazenamento nativo, redator de segredos, configuração por ambiente e varredura de repositório | comprometimento do usuário/sistema operacional foge ao aplicativo |
| Switch parcial abrir produção | Send antes dos gates operacionais | F12 obrigatória combina provider/F10/F11/F12, stable, restore, monitoramento, suporte, versão e estágio; UI não altera a política | administrador do ambiente ainda pode atestar falsamente um gate humano |
| Operador sem alçada ou volume excessivo | comunicação indevida/em massa | papel privilegiado + `email.send` + MFA + sessão, limite por lote e cota central por organização | conta privilegiada comprometida exige revogação e kill switch rápidos |
| Reuso de operação para outro conteúdo | contorno de cota/aprovação | chave tenant/operação, fingerprint SHA-256 e metadados consistentes; conflito falha fechado | colisão SHA-256 é residual teoricamente desprezível |
| Concorrência na cota | autorizações acima do limite | transação serializável, chave única e falha fechada antes do provider | conflito de serialização pode exigir nova tentativa do preflight, nunca do Send |

## Regras de registro

Logs podem conter horário, método HTTP, rota normalizada, status, duração e identificador de correlação. Não podem conter corpo, query string, CPF, CNPJ, e-mail completo, token, código OAuth, senha, nome de arquivo real ou conteúdo de PDF.

Relatos de incidente são minimizados e redigidos antes da persistência. A auditoria registra identificadores internos, estados, ator e instante; não duplica o conteúdo sensível do documento.

## Recuperação

- Catálogo de clientes: exportação protegida, restauração em modo de prévia e aplicação explícita.
- PDFs: cópia separada do acervo escolhido pelo escritório, preferencialmente em volume criptografado e com teste periódico de leitura.
- PostgreSQL: backup consistente e teste de restauração conforme `docs/OPERATIONS.md`.
- Tokens: não entram em backup; uma restauração exige nova autorização dos provedores.

## Critério para dados reais

Antes de dados reais, o escritório deve confirmar FileVault/BitLocker ativo, HTTPS e certificados OIDC de produção configurados, política de backup testada e pessoas responsáveis por incidentes. Antes de qualquer Send real, deve também concluir o piloto, stable assinada, restore isolado, monitoramento, suporte e abertura `Limited` conforme `docs/PRODUCTION.md`. Sem esses controles, o aplicativo permanece adequado apenas a dados sintéticos e homologação.
