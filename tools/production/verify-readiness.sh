#!/usr/bin/env bash
set -euo pipefail

: "${FOLHAS_PRODUCTION_BASE_URL:?Defina FOLHAS_PRODUCTION_BASE_URL com a URL HTTPS da produção.}"
: "${FOLHAS_PRODUCTION_ACCESS_TOKEN:?Defina FOLHAS_PRODUCTION_ACCESS_TOKEN a partir do cofre da sessão autorizada.}"

base_url="${FOLHAS_PRODUCTION_BASE_URL%/}"
if [[ "$base_url" != https://* ]]; then
  echo "A verificação de produção exige HTTPS." >&2
  exit 2
fi

curl --fail --silent --show-error "$base_url/health/live" >/dev/null
curl --fail --silent --show-error "$base_url/health/ready" >/dev/null
policy="$(curl --fail --silent --show-error \
  --header "Authorization: Bearer $FOLHAS_PRODUCTION_ACCESS_TOKEN" \
  "$base_url/api/production/policy")"

required=(
  '"enabled":true'
  '"environmentName":"production"'
  '"readyForSend":true'
  '"currentUserRoleAllowed":true'
  '"sendEnabled":true'
  '"minimumApplicationVersion":"0.12.0"'
  '"blockers":[]'
)
for fragment in "${required[@]}"; do
  if [[ "$policy" != *"$fragment"* ]]; then
    echo "Política de produção fechada ou incompleta: $fragment" >&2
    exit 3
  fi
done

if [[ "$policy" != *'"stage":"Limited"'* && "$policy" != *'"stage":"Gradual"'* ]]; then
  echo "A etapa precisa ser Limited ou Gradual." >&2
  exit 4
fi

echo "Produção acessível; política gradual, limites e Send autorizados para a sessão atual."
