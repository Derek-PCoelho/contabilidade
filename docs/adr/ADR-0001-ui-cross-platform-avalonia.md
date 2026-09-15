# ADR-0001: UI cross-platform com Avalonia

- Status: Proposed
- Data: 2026-08-20
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O produto precisa compartilhar a mesma base de código entre Windows, ambiente principal dos operadores, e macOS, ambiente de desenvolvimento e plataforma suportada. WPF não atende macOS. Em 2026-08-20, .NET 10 é LTS e Avalonia 12.1.1 é a versão estável corrente. A documentação atual do Avalonia classifica macOS 26 e Windows 11 24H2 como Tier 1; Windows 10 22H2 é Tier 2.

## Decisão proposta

- Usar .NET 10 LTS (`net10.0`) e Avalonia UI 12 estável com MVVM.
- Usar `CommunityToolkit.Mvvm` para observabilidade e comandos.
- Manter a UI e a lógica de aplicação independentes de APIs específicas de plataforma.
- Encapsular Keychain, Credential Manager, abertura de arquivos e atualização em interfaces na camada de aplicação, com implementações na infraestrutura.
- Publicar inicialmente `win-x64` e `osx-arm64`; avaliar `osx-x64` após confirmar máquinas Intel. `win-arm64` fica fora do MVP inicial.
- Usar o visualizador PDF do sistema como fallback inicial; um viewer embutido exige avaliação separada de licença e desempenho.
- Manter acessibilidade: teclado, texto/ícone além de cor, contraste e escala.

## Consequências

### Positivas

- Uma base C#/.NET para as duas plataformas.
- Desenvolvimento e smoke tests locais no Mac com build Windows em CI.
- Separação explícita das integrações nativas.

### Negativas e riscos

- Windows 10 22H2 está em suporte Tier 2 do Avalonia, exigindo smoke tests dedicados se continuar no escopo.
- Diferenças de menus, atalhos, fontes, diálogos e empacotamento precisam de testes reais por sistema.
- Assinatura/notarização não pode ser validada apenas por cross-compilation.

## Gate para Accepted

Na Fase 1:

1. app mínimo abre em macOS arm64;
2. build e testes passam em runners macOS e Windows;
3. artefatos dry-run são gerados para `osx-arm64` e `win-x64`;
4. um smoke test em Windows 10 22H2 confirma o suporte prometido, ou o requisito mínimo é revisado com o proprietário.

## Alternativas consideradas

- WPF: rejeitada por ser Windows-only.
- Duas UIs nativas: rejeitada pelo custo e risco de divergência.
- Electron/Tauri: não adotada; exigiria novo ADR e justificativa material.

## Referências

- https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core
- https://docs.avaloniaui.net/docs/supported-platforms
- https://docs.avaloniaui.net/docs/platform-specific-guides/windows
- https://docs.avaloniaui.net/docs/platform-specific-guides/macos
