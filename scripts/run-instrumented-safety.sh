#!/usr/bin/env bash
set -u

api_level="${1:?Android API level is required}"
evidence_dir="app/build/device-diagnostics"
mkdir -p "$evidence_dir"

set +e
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace
test_status=$?
set -e

adb logcat -d -v threadtime > "$evidence_dir/logcat-api-$api_level.txt" 2>&1 || true
adb exec-out screencap -p > "$evidence_dir/screenshot-api-$api_level.png" 2>/dev/null || true
if [[ "$test_status" -ne 0 ]]; then
    adb bugreport "$evidence_dir/bugreport-api-$api_level.zip" || true
fi

exit "$test_status"
