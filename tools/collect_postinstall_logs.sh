#!/bin/bash
# collect_postinstall_logs.sh
# Collects diagnostic logs from Android device AFTER installing a new APK
# Usage: ./collect_postinstall_logs.sh <device_serial> <package_name>

set -e

SERIAL="${1:?Usage: $0 <device_serial> <package_name>}"
PACKAGE="${2:?Usage: $0 <device_serial> <package_name>}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_DIR="${PROJECT_ROOT}/diagnostics/postinstall/${SERIAL}/${TIMESTAMP}"

echo "=============================================="
echo "Post-Install Log Collection"
echo "=============================================="
echo "Device:    ${SERIAL}"
echo "Package:   ${PACKAGE}"
echo "Timestamp: ${TIMESTAMP}"
echo "Output:    ${BASE_DIR}"
echo "=============================================="

# Create directory structure
mkdir -p "${BASE_DIR}"/{meta,logcat,dumpsys,app/files,app/cache,app/runas,bugreport}

# Meta information
echo "[1/7] Collecting device metadata..."
{
    echo "Collection Timestamp: ${TIMESTAMP}"
    echo "Device Serial: ${SERIAL}"
    echo "Package: ${PACKAGE}"
    echo ""
    echo "=== Device Properties ==="
    adb -s "${SERIAL}" shell getprop ro.product.manufacturer
    adb -s "${SERIAL}" shell getprop ro.product.model
    adb -s "${SERIAL}" shell getprop ro.build.version.release
    adb -s "${SERIAL}" shell getprop ro.build.version.sdk
    adb -s "${SERIAL}" shell getprop ro.product.cpu.abi
} > "${BASE_DIR}/meta/device_info.txt" 2>&1

# Verify app is installed
echo "[2/7] Verifying app installation..."
if adb -s "${SERIAL}" shell pm list packages | grep -qF "${PACKAGE}"; then
    echo "  App is INSTALLED"
    echo "installed" > "${BASE_DIR}/meta/install_status.txt"

    # Dumpsys package info
    echo "[3/7] Collecting package info via dumpsys..."
    adb -s "${SERIAL}" shell dumpsys package "${PACKAGE}" > "${BASE_DIR}/dumpsys/package.txt" 2>&1

    # Extract key info
    grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime|grantedPermissions|declared permissions" \
        "${BASE_DIR}/dumpsys/package.txt" > "${BASE_DIR}/dumpsys/package_summary.txt" 2>/dev/null || true

    # Dumpsys activity for services
    echo "  Collecting activity/service info..."
    adb -s "${SERIAL}" shell dumpsys activity services "${PACKAGE}" > "${BASE_DIR}/dumpsys/services.txt" 2>&1 || true

    # App storage - external
    echo "[4/7] Attempting to pull app external storage..."
    EXTERNAL_PATH="/sdcard/Android/data/${PACKAGE}"
    if adb -s "${SERIAL}" shell "ls ${EXTERNAL_PATH} 2>/dev/null" >/dev/null 2>&1; then
        echo "  External storage accessible, pulling files..."
        adb -s "${SERIAL}" pull "${EXTERNAL_PATH}/files" "${BASE_DIR}/app/files/" 2>/dev/null || echo "  No files dir or access denied"
        adb -s "${SERIAL}" pull "${EXTERNAL_PATH}/cache" "${BASE_DIR}/app/cache/" 2>/dev/null || echo "  No cache dir or access denied"
    else
        echo "  External storage not accessible (scoped storage restriction)"
        echo "Scoped storage restriction - external storage not accessible" > "${BASE_DIR}/app/external_blocked.txt"
    fi

    # App storage - internal via run-as
    echo "[5/7] Attempting to pull internal storage via run-as..."
    if adb -s "${SERIAL}" shell "run-as ${PACKAGE} ls files 2>/dev/null" >/dev/null 2>&1; then
        echo "  run-as successful, pulling internal data..."
        adb -s "${SERIAL}" exec-out "run-as ${PACKAGE} tar -cf - files cache 2>/dev/null" | \
            tar -xf - -C "${BASE_DIR}/app/runas/" 2>/dev/null || echo "  tar extraction failed"
    else
        echo "  run-as failed (app not debuggable or no internal files)"
        echo "run-as failed - app not debuggable or no internal files" > "${BASE_DIR}/app/runas_blocked.txt"
    fi
else
    echo "  ERROR: App is NOT INSTALLED after install attempt!"
    echo "not_installed" > "${BASE_DIR}/meta/install_status.txt"
    echo "[3/7] Skipping package dumpsys (not installed)"
    echo "[4/7] Skipping external storage (not installed)"
    echo "[5/7] Skipping internal storage (not installed)"
fi

# Logcat snapshot
echo "[6/7] Capturing logcat snapshot..."
adb -s "${SERIAL}" logcat -d -v time > "${BASE_DIR}/logcat/logcat_full.txt" 2>&1

# Filter for relevant entries
echo "  Filtering logcat for relevant entries..."
FILTER_PATTERN="${PACKAGE}|AndroidRuntime|FATAL EXCEPTION|ANR|StrictMode|Permission|ForegroundService|Media|AudioRecord|Camera|WebRTC|OkHttp|SSL|Timeout|childmonitor|rochefort"
grep -iE "${FILTER_PATTERN}" "${BASE_DIR}/logcat/logcat_full.txt" > "${BASE_DIR}/logcat/logcat_filtered.txt" 2>/dev/null || \
    echo "No matching entries found" > "${BASE_DIR}/logcat/logcat_filtered.txt"

# Count crashes/ANRs
CRASH_COUNT=$(grep -c "FATAL EXCEPTION" "${BASE_DIR}/logcat/logcat_full.txt" 2>/dev/null || echo "0")
ANR_COUNT=$(grep -c "ANR in" "${BASE_DIR}/logcat/logcat_full.txt" 2>/dev/null || echo "0")
echo "  Crashes found: ${CRASH_COUNT}"
echo "  ANRs found: ${ANR_COUNT}"
{
    echo "Crash count: ${CRASH_COUNT}"
    echo "ANR count: ${ANR_COUNT}"
} > "${BASE_DIR}/logcat/crash_summary.txt"

# Bugreport (best effort)
echo "[7/7] Attempting bugreport (may require device confirmation)..."
if timeout 30 adb -s "${SERIAL}" bugreport "${BASE_DIR}/bugreport/" 2>/dev/null; then
    echo "  Bugreport captured successfully"
else
    echo "  Bugreport skipped (timeout or user confirmation required)"
    echo "Bugreport skipped - timeout or requires user confirmation on device" > "${BASE_DIR}/bugreport/skipped.txt"
fi

echo ""
echo "=============================================="
echo "Collection complete: ${BASE_DIR}"
echo "=============================================="

# Generate summary
{
    echo "Post-Install Log Collection Summary"
    echo "==================================="
    echo "Device: ${SERIAL}"
    echo "Package: ${PACKAGE}"
    echo "Timestamp: ${TIMESTAMP}"
    echo "Install Status: $(cat ${BASE_DIR}/meta/install_status.txt 2>/dev/null || echo 'unknown')"
    echo ""
    echo "Files collected:"
    find "${BASE_DIR}" -type f -printf "  %p (%s bytes)\n" 2>/dev/null | head -50
} > "${BASE_DIR}/SUMMARY.txt"

cat "${BASE_DIR}/SUMMARY.txt"
