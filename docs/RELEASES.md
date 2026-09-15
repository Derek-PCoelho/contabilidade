# Atualizações e releases — Fase 10

## Estado e limites

O Desktop integra Velopack 1.2.0 (MIT) atrás de `IAppUpdateService`. A atualização é manual: o operador escolhe o canal, verifica, baixa, confere e decide quando reiniciar. Não existe aplicação silenciosa no início do processo.

Há quatro feeds independentes:

- `osx-arm64-beta` e `osx-arm64-stable`;
- `win-x64-beta` e `win-x64-stable`.

O repositório GitHub é privado e nenhum token de repositório é incorporado ao aplicativo. O feed final deve ser HTTPS, somente leitura e sem credencial na URL. Enquanto esse endpoint e os certificados não existirem, o app informa que o canal oficial ainda não foi publicado; os pacotes `validation` são artefatos técnicos não distribuíveis.

Configure o Desktop distribuído com `Phase10__UpdateFeedBaseUrl=https://...` por ambiente de implantação. `Phase10__AllowLocalUpdateFeed=true` existe exclusivamente para laboratório offline e nunca deve acompanhar o pacote de produção.

## Versionamento e canais

- Use SemVer com três componentes, por exemplo `0.10.1`.
- `beta` recebe primeiro a release assinada para smoke supervisionado.
- `stable` aceita somente versão final sem sufixo/metadata, modo `signed` e aprovação humana no environment protegido do GitHub.
- Nunca reutilize uma versão publicada, um pacote ou um diretório de saída existente.
- O canal do Desktop é uma preferência local. Trocar de canal invalida o download que ainda não foi concluído.

## Empacotamento técnico local

Os comandos abaixo criam pacotes self-contained e um manifesto `SHA256SUMS.txt`. Eles não tornam o artefato confiável para distribuição sem assinatura:

A versão 0.12.7 é uma **candidata de manutenção pós-Fase 12**. Ela corrige validação do representante, alinhamento de Competência, inventário/layout de Relatórios e filtros/fidelidade do Histórico sem alterar as fronteiras do ADR-0011. Restore bloqueado, formatação, build Release, 327 testes — 36 Domain, 95 Application, 74 Infrastructure, 33 Server e 89 UI —, auditoria NuGet sem vulnerabilidades e QA de XLSX/PDF foram aprovados localmente.

O pacote macOS arm64 `beta validation` foi gerado em `artifacts/phase10/osx-arm64-beta/0.12.7`, teve `SHA256SUMS.txt` aprovado e foi instalado como `0.12.7.0` em `/Applications/Folhas da Michelly.app`. A versão anterior `0.12.6.0` permanece recuperável em `artifacts/install-backups/Folhas da Michelly 0.12.6 pre-0.12.7.app`. O bundle é ad-hoc e falha na validação estrita de assinatura como esperado para `validation-only`: não é distribuível. O cross-publish `win-x64` contém 341 arquivos; o executável é PE32+ GUI x86-64 e tem SHA-256 `6b4872d415cdf00dd56190908df9676a527536d3140ee29cf0bc33d30bde1c0e`. Não houve smoke físico Windows.

O smoke local da versão instalada percorreu Início, Relatórios e Histórico em 1170×768 e 960×640. O commit `9119cb2` passou na CI `32873335418` em Ubuntu/PostgreSQL 18, macOS arm64 e Windows x64. O workflow `32873883491` também gerou e armazenou os pacotes internos `validation` macOS/Windows; os jobs assinados permaneceram corretamente desabilitados. Não publique nem promova a candidata antes dos gates assinados e físicos aplicáveis.

```bash
./tools/release/pack-macos.sh 0.12.7 beta validation
```

```powershell
./tools/release/pack-windows.ps1 -Version 0.12.7 -Maturity beta -Mode validation
```

Em validação local isolada no macOS, `FOLHAS_RELEASE_ARTIFACTS_ROOT` pode apontar para um diretório temporário absoluto. O padrão continua `artifacts/phase10`; raiz `/` e caminho relativo são recusados.

O modo `validation` cria `UNSIGNED-VALIDATION-ONLY.txt`. O script recusa `stable + validation`. O modo `signed` recusa execução sem os parâmetros protegidos e valida a assinatura do instalador; no macOS também valida o ticket de notarização e a avaliação do Gatekeeper.

Cada versão usa uma subpasta própria em `artifacts/phase10/<rid>-<canal>/<versão>`, evitando mistura ou sobrescrita local. Para publicar, promova somente o conteúdo da versão aprovada para a raiz do feed correspondente e preserve as releases anteriores conforme a política de retenção.

## Release assinada na CI

O workflow manual `.github/workflows/release.yml` possui três entradas: versão, canal e modo. O modo assinado usa o environment `release-beta` ou `release-stable`; configure revisão humana e restrinja os secrets a esses environments.

Windows requer uma das opções suportadas pelo Velopack:

- `WINDOWS_VPK_SIGN_PARAMS`; ou
- `WINDOWS_VPK_AZURE_TRUSTED_SIGN_FILE`.

macOS requer:

- certificado `Developer ID Application` e certificado `Developer ID Installer` em P12;
- identidades de assinatura correspondentes;
- Apple ID, senha específica de aplicativo e Team ID para notarização;
- senhas do P12 e do keychain temporário.

Os P12 são importados em keychain temporário, nunca versionados, e removidos no fim do job. A CI faz upload apenas do artefato do workflow. Ela não publica automaticamente no feed: promoção e publicação são operações separadas até o endpoint somente leitura ser provisionado.

## Promoção beta para stable

1. Execute todos os gates de código e gere `signed beta` para Windows e macOS.
2. Instale em estações isoladas, confira assinatura/notarização e faça smoke de abertura, cache SQLite, Keychain/DPAPI e atualização para a versão seguinte.
3. Compare `SHA256SUMS.txt` antes de mover qualquer arquivo.
4. Registre versão, commit, responsáveis, resultados e incidentes.
5. Somente então aprove `signed stable` no environment protegido.
6. Publique os arquivos de cada sistema exclusivamente em seu feed correspondente; nunca misture beta/stable ou RID.
7. Habilite `StableChannelEnabled` no servidor apenas depois da publicação e do smoke.

## Versão mínima e bloqueio global

O servidor expõe `GET /api/app-release-policy?currentVersion=...` para sessão autenticada e aplica:

- `Phase10:MinimumSupportedVersion`;
- `Phase10:EmailSendEnabled`;
- `Phase10:BetaChannelEnabled`;
- `Phase10:StableChannelEnabled`.

O preflight de Graph e Gmail usa a maior versão mínima entre a política global e a política do provider. Se a configuração for inválida, a versão for antiga, o servidor estiver indisponível ou o switch global estiver desligado, Send permanece bloqueado. Visualização e trabalho local não são apagados.

## Integridade, reparação e rollback

Velopack confere o pacote durante o download e o serviço traduz falha de checksum em rejeição, sem aplicar a versão. Os manifests externos também usam SHA-256 e devem ser conferidos antes da publicação.

Downgrade automático está desativado. Para reparar uma regressão:

1. desligue `Phase10:EmailSendEnabled` e o canal afetado;
2. preserve o feed e os artefatos incidentados para investigação; não sobrescreva arquivos;
3. selecione o último commit conhecido como bom;
4. gere o mesmo código como uma nova versão SemVer maior, por exemplo corrigir `0.10.2` com `0.10.3`;
5. assine, notarize, teste em beta e promova com aprovação humana;
6. reative o canal e, por último, Send;
7. registre a ocorrência e os hashes das duas releases.

Se o atualizador estiver indisponível, use o instalador assinado da versão de reparação. Não apague `Application Support/FolhasDaMichelly` nem o perfil equivalente no Windows: dados e preferências ficam fora do diretório da aplicação justamente para sobreviver à atualização/reparação.

## Gates externos pendentes

A implementação e o pacote macOS `validation` 0.12.7 foram exercitados; checksums, versão, instalação e backup recuperável foram conferidos. A assinatura é somente ad-hoc, e o publish Windows continua sendo evidência de arquitetura, não de instalação. O aceite integral do ADR-0004 permanece condicionado a:

- certificado de assinatura Windows e smoke em Windows 11 real;
- Apple Developer ID, Xcode completo e credenciais de notarização;
- endpoint HTTPS somente leitura para os quatro feeds;
- instalação beta assinada, update entre duas versões e ensaio de reparação em ambos os sistemas.

A CI multiplataforma e os pacotes internos `validation` da candidata 0.12.7 estão registrados nas execuções `32873335418` e `32873883491`. Eles não substituem os gates externos acima nem autorizam OAuth, staging/produção ou envio real.

Nenhum desses bloqueios pode ser marcado como concluído pela Fase 12 nem autoriza produção ou distribuição de artefato não assinado. Uma stable 0.12.0 assinada e validada nas duas plataformas é pré-requisito externo da abertura `Limited`; consulte `docs/PRODUCTION.md`.
