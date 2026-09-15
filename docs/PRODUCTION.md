# Produção gradual — Fase 12

## Estado atual

A infraestrutura de controle está implementada em modo fail-closed. Isso não representa deploy, aceite do piloto, publicação stable ou autorização de Send real. Os arquivos versionados mantêm F7/F8/F10/F12 fechados e não contêm segredo.

O caminho de produção só existe quando todas as camadas concordam:

```text
stable assinada + piloto aceito + restauração exercitada + monitoramento/suporte
             ↓
provider + F10 + F12 + versão mínima + papel/MFA/sessão
             ↓
cota central + idempotência + aprovação humana vigente
             ↓
provider externo
```

## Política e limites

- estágios: `Closed`, `Limited` e `Gradual`; nunca saltar diretamente de `Closed` para alto volume;
- papéis admitidos: `Manager`, `Administrator` e `OwnerTechnical`;
- defaults: 5 mensagens por lote e 20 autorizações por organização/dia;
- limites de configuração aceitos: 1–25 por lote e 1–500 por dia;
- versão mínima inicial: 0.12.0;
- a contagem diária usa data UTC e registro append-only central;
- repetir a mesma operação com o mesmo fingerprint é idempotente; mudar provider, fingerprint, lote ou quantidade de anexos exige nova operação/aprovação e a reutilização é recusada;
- `Authorized` significa somente que o preflight permitiu uma tentativa. Não significa envio, aceite do provider nem entrega.

## Pré-requisitos humanos e externos

Antes de alterar qualquer switch, registrar evidência redigida de:

1. aceite formal do piloto F11, executado em staging HTTPS com corpus sintético/anonimizado;
2. smoke físico macOS e Windows da beta assinada, incluindo Keychain/DPAPI, TOTP/OIDC e OAuth dedicado;
3. versão 0.12.0 stable assinada/notarizada, feed HTTPS somente leitura e ensaio de reparação forward-only;
4. backup consistente do PostgreSQL e do acervo, seguido de restauração em infraestrutura isolada e conferência por organização;
5. health, logs redigidos, métricas de falha/ambiguidade, alertas e responsáveis de plantão;
6. runbook de incidente exercitado, canal de suporte e autoridade nomeada para operar kill switches;
7. política LGPD, retenção, FileVault/BitLocker e base legal aprovadas antes de dados reais;
8. contas/destinos externos dedicados e requisitos Microsoft/Google concluídos, se o provider fizer parte da abertura.

## Abertura controlada

Use `deploy/production/.env.example` apenas como lista de chaves. Valores reais e certificados pertencem ao cofre/pipeline do ambiente.

1. aplique migrations em janela controlada e valide `/health/live` e `/health/ready`;
2. mantenha `Phase12__Enabled=false`, `Stage=Closed` e todos os switches de Send falsos durante o smoke;
3. publique a stable assinada e confirme a política de versão mínima;
4. registre os sete gates F12 como verdadeiros somente após evidência e aprovação humana;
5. selecione `Stage=Limited`, mantenha os defaults 5/20 e configure apenas os papéis autorizados;
6. abra primeiro o provider escolhido, depois F10 e, por último, `Phase12__AllowSend`/`Enabled` em uma mudança revisada;
7. com uma sessão de papel permitido, permissão `email.send` e MFA, execute a verificação read-only:

```bash
export FOLHAS_PRODUCTION_BASE_URL='https://producao.exemplo.invalid'
export FOLHAS_PRODUCTION_ACCESS_TOKEN='TOKEN_EFÊMERO_DO_COFRE'
bash tools/production/verify-readiness.sh
```

8. comece com uma única organização e lote mínimo; confira destinatário, anexos, fingerprint e aprovação antes de cada operação;
9. observe autorizações, recusas, falhas, ambiguidade e incidentes antes de ampliar organizações ou passar a `Gradual`.

O script não altera estado e nunca envia mensagem. Não grave o token no projeto, em arquivo `.env` ou no histórico do shell.

## Parada imediata

Em mistura de cliente, destinatário indevido, PII/token em log, duplicidade, ambiguidade sem reconciliação, falha de backup, alerta indisponível ou incidente alto/crítico:

1. altere `Phase12__AllowSend=false` e/ou `Phase12__Enabled=false`;
2. desligue `Phase10__EmailSendEnabled` e o switch do provider afetado;
3. preserve banco, autorização append-only, tentativa e evidência redigida;
4. revogue sessões/tokens quando aplicável e abra ocorrência;
5. não repita Send ambíguo; reconcilie pelo mesmo provider;
6. restaure somente em ambiente isolado e publique correção com SemVer maior;
7. reabra em `Limited` apenas após análise, correção, novos gates e autorização humana.

## Critério para ampliar ou encerrar

Ampliar somente quando não houver mistura, duplicidade, ambiguidade aberta, falha alta/crítica, regressão de versão ou violação de quota durante a janela definida pelo responsável operacional. A aplicação não promove `Limited` para `Gradual` automaticamente.

Se qualquer pré-requisito externo continuar ausente, o resultado correto é `Closed`, bloqueadores visíveis e Send indisponível.
