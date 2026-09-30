# Empacotamento e assinatura do Desktop (Java)

## Geração

`./gradlew :desktop:packageNative -PappVersion=X.Y.Z` roda o `jpackage` do JDK 25 sobre `installDist`. O resultado é um instalador com runtime Java próprio (cerca de 190 MB descompactado).

| Sistema | Tipo | Observações |
|---|---|---|
| Windows x64 | MSI por usuário, com menu e atalho | `--win-upgrade-uuid` fixo: novas versões substituem a anterior. Exige WiX Toolset 3.x no PATH. |
| macOS arm64 | DMG | Identificador `br.com.contadoresassociados.folhas`. |
| Linux | app-image | Apenas para testes e pré-visualização. |

Os ícones ficam em `java/desktop/packaging/` e são os mesmos da versão .NET.

## Assinatura

Os certificados **nunca** entram no repositório. Eles ficam nos segredos do pipeline de release.

**Windows (Authenticode):**
```powershell
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /f $env:CERT_PFX /p $env:CERT_PASSWORD "Folhas da Michelly-X.Y.Z.msi"
```

**macOS (Developer ID + notarização):**
```bash
./gradlew :desktop:packageNative -PappVersion=X.Y.Z   # e depois:
codesign --deep --force --options runtime --sign "Developer ID Application: <Equipe>" "Folhas da Michelly.app"
xcrun notarytool submit "Folhas da Michelly-X.Y.Z.dmg" --keychain-profile folhas --wait
xcrun stapler staple "Folhas da Michelly-X.Y.Z.dmg"
```

## Publicação no canal de atualização

O atualizador (`SignedFeedAppUpdateService`) só aceita pacotes que cumprem três condições:

1. O manifesto `releases-<plataforma>-<canal>.json` está assinado com uma das chaves públicas configuradas (`FOLHAS_UPDATE_PUBLIC_KEYS`).
2. O `sequence` é maior que o último aceito, o que impede voltar a uma versão antiga (anti-replay).
3. A assinatura do sistema operacional é do publicador esperado (`FOLHAS_UPDATE_PUBLISHER`).

Para gerar o manifesto assinado, use `ReleaseSigner` (módulo infrastructure) no pipeline, com a chave privada vinda dos segredos.
