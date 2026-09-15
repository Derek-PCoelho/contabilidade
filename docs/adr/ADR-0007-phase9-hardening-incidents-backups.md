# ADR-0007 — Hardening, incidentes e cópia protegida do catálogo

- Status: Aceito para a Fase 9
- Data: 2026-08-21

## Contexto

O aplicativo já separa clientes, exige revisão humana e registra auditoria, mas a Fase 8 ainda deixava quatro lacunas operacionais: limitação de requisições sem partição por origem, ausência de um fluxo explícito de incidentes, cópia do catálogo em JSON legível e listas históricas sem uma política clara de contenção.

## Decisão

1. A API aplica limites distintos para autenticação, leitura, escrita e operações sensíveis. As partições usam o usuário autenticado quando disponível e, caso contrário, a origem da conexão. Uma resposta bloqueada é genérica, contém `Retry-After` e um identificador de correlação, sem ecoar corpo, consulta, credencial ou dado pessoal.
2. O aplicativo mantém incidentes locais e imutáveis por histórico. Um incidente referencia uma tentativa de entrega, usa estados controlados e exige justificativa para resolução ou encerramento. Texto livre passa por redação defensiva antes de persistir.
3. A cópia exportável do catálogo usa envelope versionado com AES-256-GCM e chave derivada por PBKDF2-HMAC-SHA-256. A senha nunca é persistida. Senha incorreta ou conteúdo alterado produzem a mesma falha genérica.
4. A cópia do catálogo não inclui PDFs, cache de reconhecimento, credenciais ou tokens. O acervo de PDFs e o banco central têm procedimentos de backup separados; a interface declara essa fronteira.
5. Retenção continua manual e não destrutiva nesta fase. O aplicativo pode apontar itens antigos, mas não remove automaticamente documentos ou auditoria.
6. Acessibilidade é tratada com nomes de automação, ordem de foco, atalhos de navegação, regiões vivas para estado e listas virtualizadas/limitadas onde o volume pode crescer.
7. Não será adicionada dependência de produção para esta decisão. A criptografia usa primitivas da biblioteca padrão do .NET.

## Consequências

- O catálogo exportado deixa de ser diretamente inspecionável; a senha precisa ser guardada fora do aplicativo e não pode ser recuperada.
- A restauração valida e apresenta uma prévia antes de aplicar alterações, preservando a regra de não sobrescrever silenciosamente.
- AES-GCM protege a cópia exportada, mas não transforma o SQLite local nem pastas escolhidas pelo usuário em volumes criptografados. FileVault no macOS e BitLocker no Windows continuam controles operacionais obrigatórios para dados reais.
- A limitação reduz abuso e exaustão, mas não substitui firewall, proxy reverso, monitoramento ou política de identidade.
- Incidentes não podem ser apagados pela interface. Correções geram novos eventos de auditoria.

## Alternativas rejeitadas

- Guardar senha de backup no aplicativo: cria um segredo recuperável junto do próprio dado.
- Criptografia própria ou modo CBC sem autenticação: aumenta o risco de adulteração silenciosa.
- Um único limite global: permite que um usuário bloqueie os demais e não diferencia custo das rotas.
- Exclusão automática por idade: perigosa para obrigações fiscais, auditoria e documentos com retenções diferentes.
- Logar corpo, consulta ou endereço de e-mail para diagnóstico: amplia exposição e contraria minimização de dados.

