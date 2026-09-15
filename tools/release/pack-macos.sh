#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Uso: pack-macos.sh <versao-semver> <beta|stable> <validation|signed>" >&2
  exit 2
fi

release_version="$1"
release_maturity="$2"
release_mode="$3"
if [[ ! "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Versão inválida: use SemVer com três componentes." >&2
  exit 2
fi

if [[ "$release_maturity" != "beta" && "$release_maturity" != "stable" ]]; then
  echo "Canal inválido: use beta ou stable." >&2
  exit 2
fi

if [[ "$release_mode" != "validation" && "$release_mode" != "signed" ]]; then
  echo "Modo inválido: use validation ou signed." >&2
  exit 2
fi

if [[ "$release_maturity" == "stable" && "$release_mode" != "signed" ]]; then
  echo "O canal stable exige assinatura e notarização." >&2
  exit 3
fi

if [[ "$release_maturity" == "stable" && ! "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "O canal stable exige uma versão final sem sufixo ou metadata." >&2
  exit 3
fi

if [[ "$release_mode" == "signed" ]]; then
  : "${VPK_SIGN_APP_IDENTITY:?Defina VPK_SIGN_APP_IDENTITY.}"
  : "${VPK_SIGN_INSTALL_IDENTITY:?Defina VPK_SIGN_INSTALL_IDENTITY.}"
  : "${VPK_NOTARY_PROFILE:?Defina VPK_NOTARY_PROFILE.}"
fi

release_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
release_repo_root="$(cd "$release_script_dir/../.." && pwd)"
release_rid="osx-arm64"
release_channel="$release_rid-$release_maturity"
release_artifacts_root="${FOLHAS_RELEASE_ARTIFACTS_ROOT:-$release_repo_root/artifacts/phase10}"
if [[ "$release_artifacts_root" != /* || "$release_artifacts_root" == "/" ]]; then
  echo "FOLHAS_RELEASE_ARTIFACTS_ROOT deve ser um diretório absoluto específico." >&2
  exit 2
fi

release_publish_dir="$release_artifacts_root/publish/$release_channel/$release_version"
release_output_dir="$release_artifacts_root/$release_channel/$release_version"

if [[ -d "$release_output_dir" ]] &&
  find "$release_output_dir" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  echo "O diretório de saída já contém artefatos. Preserve-o ou remova-o conscientemente antes de repetir a release." >&2
  exit 4
fi

mkdir -p "$release_publish_dir" "$release_output_dir"
dotnet tool restore
dotnet publish "$release_repo_root/src/FolhasDaMichelly.Desktop/FolhasDaMichelly.Desktop.csproj" \
  --configuration Release \
  --runtime "$release_rid" \
  --self-contained true \
  --no-restore \
  -p:Version="$release_version" \
  --output "$release_publish_dir"

dotnet tool run vpk -- pack \
  --packId "FolhasDaMichelly" \
  --packVersion "$release_version" \
  --packDir "$release_publish_dir" \
  --mainExe "FolhasDaMichelly.Desktop" \
  --packTitle "Folhas da Michelly" \
  --runtime "$release_rid" \
  --channel "$release_channel" \
  --bundleId "br.com.contadoresassociados.folhasdamichelly" \
  --icon "$release_repo_root/src/FolhasDaMichelly.Desktop/Assets/Branding/app-icon.icns" \
  --outputDir "$release_output_dir"

if [[ "$release_mode" == "signed" ]]; then
  installer_path="$(find "$release_output_dir" -maxdepth 1 -type f -name '*-Setup.pkg' -print -quit)"
  if [[ -z "$installer_path" ]]; then
    echo "O instalador macOS assinado não foi produzido." >&2
    exit 5
  fi

  pkgutil --check-signature "$installer_path"
  xcrun stapler validate "$installer_path"
  spctl --assess --type install --verbose=4 "$installer_path"
fi

if [[ "$release_mode" == "validation" ]]; then
  printf '%s\n' \
    "PACOTE NÃO ASSINADO — SOMENTE VALIDAÇÃO TÉCNICA DA FASE 10." \
    "NÃO DISTRIBUIR E NÃO PUBLICAR EM FEED DE ATUALIZAÇÃO." \
    > "$release_output_dir/UNSIGNED-VALIDATION-ONLY.txt"
fi

(
  cd "$release_output_dir"
  find . -maxdepth 1 -type f ! -name 'SHA256SUMS.txt' -print0 \
    | sort -z \
    | xargs -0 shasum -a 256 \
    > SHA256SUMS.txt
)

(
  cd "$release_output_dir"
  shasum -a 256 -c SHA256SUMS.txt
)

echo "Pacote $release_channel gerado em $release_output_dir"
