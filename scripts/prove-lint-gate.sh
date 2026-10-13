#!/usr/bin/env bash
set -uo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

fixture_source=security/fixtures/lint/LintGateFixture.java
fixture_dir=app/src/main/java/de/rochefort/childmonitor/lintgate
fixture_target=$fixture_dir/LintGateFixture.java
evidence=app/build/reports/lint-gate-fixture.log

if [ -e "$fixture_target" ]; then
  echo "refusing to overwrite existing source: $fixture_target" >&2
  exit 1
fi

mkdir -p "$fixture_dir" "$(dirname "$evidence")"
cp "$fixture_source" "$fixture_target"
cleanup() {
  rm -f "$fixture_target"
  rmdir "$fixture_dir" 2>/dev/null || true
}
trap cleanup EXIT

set +e
./gradlew :app:lintDebug --no-daemon --no-configuration-cache >"$evidence" 2>&1
lint_status=$?
set -e

if [ "$lint_status" -eq 0 ]; then
  echo "lint gate accepted an intentional API-35 call in the minSdk-34 app" >&2
  cat "$evidence" >&2
  exit 1
fi

report=app/build/reports/lint-results-debug.xml
# GitHub's baseline Ubuntu image does not guarantee ripgrep. This release gate deliberately uses
# POSIX grep so the proof cannot fail merely because an optional developer search tool is absent.
if [ ! -f "$report" ] || ! grep -q 'id="NewApi"' "$report" || ! grep -q 'LintGateFixture.java' "$report"; then
  echo "lint failed, but not for the intentional NewApi fixture" >&2
  cat "$evidence" >&2
  exit 1
fi

echo "lint gate verified: intentional NewApi fixture was rejected"
