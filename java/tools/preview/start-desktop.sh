#!/usr/bin/env bash
# (Re)starts the JavaFX desktop on the virtual display used by the noVNC preview.
set -euo pipefail
cd "$(dirname "$0")/../.."
source ../.tools/env.sh 2>/dev/null || true
LOG_DIR="${PREVIEW_LOG_DIR:-/tmp/folhas-preview}"; mkdir -p "$LOG_DIR"
pkill -f 'folhas.desktop.DesktopLauncher' 2>/dev/null || true
./gradlew -q :desktop:installDist
export DISPLAY="${PREVIEW_DISPLAY:-:99}"
export FOLHAS_DESKTOP_DATA_DIR="${FOLHAS_DESKTOP_DATA_DIR:-/tmp/folhas-preview/desktop-data}"
export FOLHAS_DESKTOP_ALLOW_MEMORY_VAULT=true
export FOLHAS_DESKTOP_MAXIMIZED=true
export FOLHAS_DESKTOP_INPUT_FOLDER="${FOLHAS_DESKTOP_INPUT_FOLDER:-$LOG_DIR/exemplos}"
# Perfil Local por padrão. Para testar o login no Server: PREVIEW_CONNECTED=1 (usa o Server em :8080).
if [[ "${PREVIEW_CONNECTED:-0}" == "1" ]]; then
  export FOLHAS_API_BASE_ADDRESS="${FOLHAS_API_BASE_ADDRESS:-http://127.0.0.1:8080/}"
else
  unset FOLHAS_API_BASE_ADDRESS
fi
export JAVA_OPTS="${JAVA_OPTS:-} -Dprism.order=sw -Dprism.lcdtext=false"
# O cofre em memória perde a chave a cada reinício: a pré-visualização recria o banco local.
rm -rf "$FOLHAS_DESKTOP_DATA_DIR"
# PDFs fictícios para testar a importação (Documentos → Usar uma pasta inteira).
LIB=desktop/build/install/folhas-da-michelly/lib
java -cp "$LIB/*" tools/preview/SamplePdfs.java "$LOG_DIR/exemplos" >/dev/null 2>&1 || true
setsid nohup desktop/build/install/folhas-da-michelly/bin/folhas-da-michelly >"$LOG_DIR/desktop.log" 2>&1 < /dev/null &
sleep 6
tail -5 "$LOG_DIR/desktop.log" || true
echo "Desktop started on $DISPLAY"
