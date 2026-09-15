#!/usr/bin/env bash
set -euo pipefail

: "${FOLHAS_STAGING_BASE_URL:?Defina FOLHAS_STAGING_BASE_URL com a URL HTTPS da homologação.}"
: "${FOLHAS_STAGING_ACCESS_TOKEN:?Defina FOLHAS_STAGING_ACCESS_TOKEN a partir do cofre da sessão de teste.}"

base_url="${FOLHAS_STAGING_BASE_URL%/}"
if [[ "$base_url" != https://* && "$base_url" != http://localhost:* && "$base_url" != http://127.0.0.1:* ]]; then
  echo "A homologação remota exige HTTPS." >&2
  exit 2
fi

curl --fail --silent --show-error "$base_url/health/live" >/dev/null
curl --fail --silent --show-error "$base_url/health/ready" >/dev/null
policy="$(curl --fail --silent --show-error \
  --header "Authorization: Bearer $FOLHAS_STAGING_ACCESS_TOKEN" \
  "$base_url/api/pilot/policy")"

required=(
  '"enabled":true'
  '"environmentName":"staging"'
  '"allowTest":true'
  '"allowDraft":true'
  '"allowSend":false'
  '"requireNonProductionData":true'
  '"maximumClients":5'
  '"minimumApplicationVersion":"0.12.0"'
)
for fragment in "${required[@]}"; do
  if [[ "$policy" != *"$fragment"* ]]; then
    echo "Política de homologação inválida ou incompleta: $fragment" >&2
    exit 3
  fi
done

echo "Homologação acessível e política do piloto fechada para Send."
