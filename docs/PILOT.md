# Piloto supervisionado — Fase 11

## Objetivo e limite

O piloto valida operação, ergonomia, segurança e recuperação em homologação com no máximo cinco clientes fictícios ou anonimizados. Ele não é produção: `Send` permanece ausente da interface e bloqueado no workflow e no servidor. Somente `Teste seguro` e `Salvar como rascunho` podem ser exercitados.

## Preparação do ambiente

1. provisionar PostgreSQL 18 separado, TLS válido, host restrito e backup próprio;
2. aplicar secrets pelo cofre do ambiente a partir de `deploy/staging/.env.example`, nunca por arquivo no Git;
3. manter `ASPNETCORE_ENVIRONMENT=Staging`, `Phase11:EnvironmentName=staging` e todos os switches de Send em `false`;
4. publicar somente um beta assinado. Artefato `validation` da Fase 10 continua não distribuível;
5. confirmar `/health/live`, `/health/ready` e a política autenticada com `bash tools/pilot/verify-staging.sh`;
6. criar uma organização exclusiva de homologação, usuários nominativos, MFA e permissões mínimas.

O script recebe `FOLHAS_STAGING_BASE_URL` e `FOLHAS_STAGING_ACCESS_TOKEN` apenas pelo ambiente atual. Ele não imprime o token nem altera dados.

## Dados admitidos

- PDFs e cadastros sintéticos com marca visível de não validade; ou corpus formalmente anonimizado e revisado;
- e-mails exclusivamente em `example.invalid` no modo local;
- se Graph/Gmail forem homologados, conta dedicada e único destino controlado aprovados;
- nenhum CPF/CNPJ, e-mail, nome, token ou PDF real no Git, nos artefatos de CI ou nos relatórios compartilhados.

Antes de importar, um segundo revisor confirma que não há dado real. A confirmação é registrada no painel do piloto, mas nomes, arquivos e evidências pessoais não entram no checklist.

## Roteiro funcional mínimo

| Etapa | Evidência esperada | Condição de parada |
|---|---|---|
| Cadastrar | PF/PJ sintéticos, filiais, sócios e destinatário de teste | mistura de organização, validação incorreta ou dado real |
| Importar | sete perfis sintéticos, duplicado e arquivo inválido | travamento, hash divergente ou duplicado elegível |
| Conferir | blockers fora de lote; alteração invalida aprovação | grupo errado, período errado ou aprovação preservada após mudança |
| Testar | destino controlado, assunto marcado e nenhuma cópia ao cliente | qualquer rota para endereço original |
| Rascunhar | rascunho conferível, sem conclusão de entrega | envio, repetição cega ou resultado não auditado |
| Relatar | XLSX/CSV por competência, sem fórmula injetável | divergência com o snapshot ou PII desnecessária |
| Recuperar | restart, ambiguidade, backup/restore e rollback forward-only | perda de estado, repetição ou restauração não verificada |

## Checklist por estação

### macOS

- beta assinada e notarizada instalada em usuário não administrador;
- Gatekeeper, ícone, abertura, atalhos, drag-and-drop e seletor de pastas conferidos;
- Keychain mantém/revoga a sessão após reinício e atualização;
- FileVault, permissões do acervo e backup externo confirmados;
- Test/Draft e recuperação exercitados sem Send;
- rollback para versão assinada posterior ou reparação ensaiado, sem downgrade inseguro.

### Windows 11

- beta Authenticode instalada em usuário padrão e SmartScreen conferido;
- ícone, abertura, atalhos, drag-and-drop, seletor e Excel dos relatórios conferidos;
- DPAPI CurrentUser mantém/revoga a sessão após reinício e atualização;
- BitLocker, ACL do acervo e backup externo confirmados;
- Test/Draft e recuperação exercitados sem Send;
- reparação/rollback forward-only ensaiado.

Cada confirmação no painel é por organização e mantém auditoria append-only. Desmarcar um item revoga a prontidão; não apaga o evento anterior.

## Métricas e limites de parada

O painel exibe somente contagens agregadas, nunca cliente, arquivo, destinatário ou texto:

- clientes distintos e documentos processados;
- elegíveis, bloqueados e duplicados;
- tentativas Test e Draft;
- tentativas Send, que devem permanecer exatamente em zero;
- falhas, pendências ou resultados ambíguos;
- ocorrências altas/críticas ainda abertas.

Interromper imediatamente o piloto se houver tentativa Send, mistura de cliente/organização, duplicidade encaminhada, token/PII em log, ocorrência alta/crítica, ambiguidade sem reconciliação ou falha de backup/restore. Preservar evidência redigida, desligar os switches centrais, revogar sessões afetadas e seguir `docs/OPERATIONS.md`.

## Rollback

1. colocar `Phase11:Enabled=false` e todos os switches de envio em `false` no servidor;
2. revogar sessões/dispositivos do piloto e bloquear o acesso à organização de homologação;
3. preservar banco, auditoria e acervo somente leitura conforme a política de incidente;
4. restaurar em infraestrutura isolada e comparar contagens/hashes;
5. publicar versão corretiva assinada com número maior; não distribuir pacote validation nem sobrescrever feed;
6. reabrir apenas após causa, correção, testes e nova aprovação operacional.

## Aceite da Fase 11

O código e a preparação local podem ser aprovados com todos os gates automatizados verdes. O aceite operacional integral continua bloqueado até existirem beta assinada, staging HTTPS, conta controlada quando aplicável e smoke físico em macOS e Windows. O painel não permite marcar essas condições automaticamente e não transforma ausência de evidência em sucesso.
