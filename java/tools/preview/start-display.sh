#!/usr/bin/env bash
# Starts a virtual display (:99) + VNC + noVNC (http://localhost:6080/vnc.html)
# so the JavaFX desktop can be watched from a browser during development.
set -euo pipefail
DISPLAY_NUM="${PREVIEW_DISPLAY:-:99}"
GEOMETRY="${PREVIEW_GEOMETRY:-1440x900x24}"
NOVNC_PORT="${PREVIEW_NOVNC_PORT:-6080}"
LOG_DIR="${PREVIEW_LOG_DIR:-/tmp/folhas-preview}"
mkdir -p "$LOG_DIR"

if ! pgrep -f "Xvfb $DISPLAY_NUM" >/dev/null; then
  setsid nohup Xvfb "$DISPLAY_NUM" -screen 0 "$GEOMETRY" -ac +extension GLX +render -noreset >"$LOG_DIR/xvfb.log" 2>&1 < /dev/null &
  sleep 1
fi
export DISPLAY="$DISPLAY_NUM"
pgrep -x fluxbox >/dev/null || setsid nohup fluxbox >"$LOG_DIR/fluxbox.log" 2>&1 < /dev/null &
pgrep -x x11vnc >/dev/null || setsid nohup x11vnc -display "$DISPLAY_NUM" -forever -shared -nopw -rfbport 5900 -quiet >"$LOG_DIR/x11vnc.log" 2>&1 < /dev/null &
sleep 1
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
port_open "$NOVNC_PORT" || setsid nohup websockify --web /usr/share/novnc "$NOVNC_PORT" localhost:5900 >"$LOG_DIR/novnc.log" 2>&1 < /dev/null &
sleep 1
echo "Display $DISPLAY_NUM ready. noVNC: http://localhost:$NOVNC_PORT/vnc.html?autoconnect=1&resize=scale"
