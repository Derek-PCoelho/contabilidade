# ADR-0004: Atualização e distribuição desktop

- Status: Proposed
- Data: 2026-08-20
- Decisores: proprietário técnico e equipe do projeto

## Contexto

O produto precisa de canais beta/stable, pacotes Windows/macOS, rollback e atualização verificável. O repositório de código pode ser privado, mas um aplicativo instalado não pode carregar token pessoal para consultar releases privados. Atualização sem assinatura amplia risco de supply chain.

## Decisão proposta

- Manter `IAppUpdateService` para desacoplar UI e domínio do atualizador.
- Adotar Velopack 1.2.0 estável como candidato principal, condicionado à conclusão dos gates assinados da Fase 10.
- Publicar feeds separados por plataforma, arquitetura e canal: `win-x64` e `osx-arm64`, cada um em `beta` e `stable`.
- Hospedar pacotes/metadata assinados em endpoint somente leitura apropriado; nenhum token pessoal será embutido no Desktop.
- Releases partem de tag SemVer, após CI, assinatura, notarização macOS, smoke test e promoção humana de beta para stable.
- O servidor mantém `minimum_supported_version` e `email.send.enabled`; versão insegura pode continuar em visualização/Test conforme política, mas Send é bloqueado.
- Manter release anterior e procedimento documentado de reparação/rollback.

## Consequências

- Velopack é MIT, ativo e cross-platform, mas introduz formato/processo de empacotamento que precisa ser exercitado nas duas plataformas.
- Assinatura Windows exige certificado e armazenamento seguro da chave; macOS exige Developer ID, hardened runtime e notarização.
- O macOS App Sandbox não é suportado pelo Velopack; distribuição pela Mac App Store exigiria outra estratégia.

## Gate para Accepted

Na Fase 10:

1. instalar versão beta assinada em Windows e macOS;
2. atualizar para a versão seguinte sem perder dados;
3. rejeitar feed/pacote adulterado;
4. testar downgrade/reparação documentado;
5. confirmar que nenhum segredo de publicação está no binário;
6. validar promoção beta → stable com aprovação humana.

## Evidência de implementação da Fase 10

O adaptador, a inicialização precoce do Velopack, os quatro canais, o pacote macOS `validation`, os manifests SHA-256, a CI protegida, a política global de versão/Send e os testes de adulteração foram implementados. O ADR permanece `Proposed`, pois certificados Windows/Apple, Xcode completo, feed HTTPS e estações de smoke assinadas não estão disponíveis; portanto os itens 1, 2, 4 e 6 do gate ainda não podem ser declarados concluídos.

O procedimento de promoção e reparação sem downgrade está em `docs/RELEASES.md`. Nenhum pacote não assinado pode ser publicado ou promovido.

## Alternativas consideradas

- Atualização manual: aceitável apenas como fallback inicial, não como estado final.
- Feed em release privado com token embutido: rejeitado.
- Atualização não assinada: rejeitada para produção.

## Referências

- https://docs.velopack.io/
- https://docs.velopack.io/packaging/operating-systems/macos
- https://developer.apple.com/documentation/security/notarizing_macos_software_before_distribution
