#!/usr/bin/env bash
set -euo pipefail

# Debug and instrumentation APKs must already be installed on the chosen Android 14+ emulator.
# Evidence verifies saved encrypted credentials through a real app process death.
serial="${1:?Pass the emulator adb serial}"
app_package=de.rochefort.childmonitor
runner=$app_package.test/androidx.test.runner.AndroidJUnitRunner
test_class=$app_package.ProcessRestartPairingInstrumentedTest
api=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ ! "$api" =~ ^[0-9]+$ ]] || (( api < 34 )); then
    echo 'Process restart pairing validation requires Android 14 or newer.' >&2
    exit 1
fi

evidence_label=${serial//[^a-zA-Z0-9._-]/_}
evidence_dir=app/build/device-diagnostics
mkdir -p "$evidence_dir"
prepare_log=$evidence_dir/process-restart-$evidence_label-prepare.log
restore_log=$evidence_dir/process-restart-$evidence_label-restore.log

adb -s "$serial" shell am instrument -w -r \
    -e class "$test_class#a_prepareTwoIndependentParents" "$runner" > "$prepare_log" 2>&1
if ! grep -q 'OK (1 test)' "$prepare_log"; then
    cat "$prepare_log" >&2
    exit 1
fi

adb -s "$serial" shell am force-stop "$app_package"
adb -s "$serial" shell am instrument -w -r \
    -e class "$test_class#b_restoreBothParentsWithoutPairingAgain" \
    -e requireProcessRestart true "$runner" > "$restore_log" 2>&1
if ! grep -q 'OK (1 test)' "$restore_log"; then
    cat "$restore_log" >&2
    exit 1
fi

echo 'Both parent credentials survived process restart and restored simultaneous pinned audio.'
echo "Evidence saved under $evidence_dir."
