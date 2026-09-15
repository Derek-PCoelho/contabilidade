# AGENTS.md

## Projeto

Folhas da Michelly é um aplicativo desktop Avalonia com backend ASP.NET Core para reconhecer documentos contábeis, preparar e-mails e manter auditoria sem misturar dados entre clientes.

## Regras obrigatórias

- Leia `docs/specs/MEGA_PROMPT_FOLHAS_DA_MICHELLY_CODEX.md`, o Blueprint e os ADRs relacionados antes de mudanças arquiteturais.
- Execute somente a fase explicitamente autorizada e atualize `docs/PROGRESS.md` ao concluir.
- Preserve Windows e macOS; não use WPF nem espalhe condicionais de sistema operacional.
- Use somente dados operacionais/de clientes e PDFs sintéticos nos testes automatizados. Arquivos, imagens e dados fornecidos pelo proprietário podem ser usados, empacotados, versionados e ter sua integridade testada quando houver autorização explícita para os arquivos e a finalidade; registre origem, escopo e uso na documentação.
- A autorização de um asset não se estende implicitamente a outros materiais. Credenciais, tokens e segredos nunca entram no Git; documentos contábeis operacionais continuam fora do repositório salvo nova autorização nominal e revisão de segurança.
- Nunca armazene senha de Gmail/Outlook, token ou credencial no repositório. Outros dados reais seguem a regra de autorização nominal, finalidade e documentação acima.
- Não envie e-mail real sem uma fase de integração controlada explicitamente autorizada.
- Não use automação de navegador/webmail nem IA para decidir cliente ou destinatário.
- Não adicione dependência de produção sem verificar licença, versão estável e manutenção.
- Toda alteração após aprovação deve invalidar a aprovação; bloqueados não entram em aprovação em lote.

## Comandos esperados após o scaffold

```bash
dotnet restore
dotnet format FolhasDaMichelly.slnx --no-restore --verify-no-changes
dotnet build --configuration Release --no-restore
dotnet test --configuration Release --no-build
dotnet run --project src/FolhasDaMichelly.Desktop
dotnet run --project src/FolhasDaMichelly.Server
```

## Definition of Done

Build limpo nas plataformas pertinentes, testes e analyzers passando, documentação/progresso atualizados, nenhuma credencial versionada e nenhum arquivo real sem autorização registrada e finalidade definida.
