#!/usr/bin/env bash
set -euo pipefail

PKG="de.rochefort.childmonitor"
CHILD_SERIAL="${1:?Usage: $0 CHILD_SERIAL PARENT_SERIAL CHILD_VPN_IP}"
PARENT_SERIAL="${2:?Usage: $0 CHILD_SERIAL PARENT_SERIAL CHILD_VPN_IP}"
CHILD_VPN_IP="${3:?Usage: $0 CHILD_SERIAL PARENT_SERIAL CHILD_VPN_IP}"

WORK_DIR="${TMPDIR:-/tmp}/cm_e2e_vpn_video"
mkdir -p "$WORK_DIR"
PARENT_LOG="$WORK_DIR/parent_logcat.txt"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd adb
require_cmd rg

get_bounds() {
  local dev="$1" rid="$2" xml line
  xml="$WORK_DIR/window_${dev}.xml"
  adb -s "$dev" shell uiautomator dump /sdcard/window_dump.xml >/dev/null
  adb -s "$dev" pull /sdcard/window_dump.xml "$xml" >/dev/null
  line="$(sed 's/></>\n</g' "$xml" | rg "resource-id=\"$rid\"" | head -n1 || true)"
  if [ -z "$line" ]; then
    return 1
  fi
  echo "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'
}

tap_rid() {
  local dev="$1" rid="$2" b x1 y1 x2 y2 cx cy
  b="$(get_bounds "$dev" "$rid")"
  read -r x1 y1 x2 y2 <<< "$b"
  cx=$(((x1 + x2) / 2))
  cy=$(((y1 + y2) / 2))
  adb -s "$dev" shell input tap "$cx" "$cy"
}

echo "Resetting app state and logs..."
adb -s "$PARENT_SERIAL" logcat -c
adb -s "$CHILD_SERIAL" logcat -c
adb -s "$PARENT_SERIAL" shell am force-stop "$PKG"
adb -s "$CHILD_SERIAL" shell am force-stop "$PKG"

echo "Starting child mode on $CHILD_SERIAL..."
adb -s "$CHILD_SERIAL" shell am start -n "$PKG/de.rochefort.childmonitor.StartActivity" >/dev/null
sleep 1
tap_rid "$CHILD_SERIAL" "$PKG:id/useChildDevice"
sleep 7

echo "Starting parent mode quick reconnect on $PARENT_SERIAL..."
adb -s "$PARENT_SERIAL" shell am start -n "$PKG/de.rochefort.childmonitor.StartActivity" >/dev/null
sleep 1
tap_rid "$PARENT_SERIAL" "$PKG:id/quickParentReconnect"
sleep 10

echo "Opening fullscreen video..."
tap_rid "$PARENT_SERIAL" "$PKG:id/cameraPreviewImage"
sleep 5

adb -s "$PARENT_SERIAL" logcat -d > "$PARENT_LOG"

echo "Validating logs..."
rg -q "Remote endpoint updated .* -> ${CHILD_VPN_IP}:11000" "$PARENT_LOG"
rg -q "Snapshot frame received successfully from http://${CHILD_VPN_IP}:11000/snapshot" "$PARENT_LOG"
rg -q "Fullscreen camera opened with URL: http://${CHILD_VPN_IP}:11000/snapshot" "$PARENT_LOG"
rg -q "Fullscreen first frame received from http://${CHILD_VPN_IP}:11000/snapshot" "$PARENT_LOG"

echo "Validating direct VPN snapshot response..."
SNAPSHOT_HEAD="$(adb -s "$PARENT_SERIAL" shell "printf 'GET /snapshot HTTP/1.1\r\nHost: ${CHILD_VPN_IP}\r\nConnection: close\r\n\r\n' | nc -w 5 ${CHILD_VPN_IP} 11000 | head -n 1" | tr -d '\r')"
if [[ "$SNAPSHOT_HEAD" != "HTTP/1.1 200 OK " && "$SNAPSHOT_HEAD" != "HTTP/1.1 200 OK" ]]; then
  echo "Unexpected snapshot response: $SNAPSHOT_HEAD" >&2
  exit 1
fi

echo "PASS: VPN video pipeline works end-to-end."
echo "Parent logs: $PARENT_LOG"
