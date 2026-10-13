#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradle_java_home=${CM_GRADLE_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
evidence_dir=$(mktemp -d /tmp/child-monitor-repro.XXXXXX)
trap 'rm -rf -- "$evidence_dir"' EXIT

cd "$repo_root"

build_release() {
  ./gradlew clean assembleRelease \
    -Dorg.gradle.java.home="$gradle_java_home" \
    --no-daemon --stacktrace
}

build_release
first_apk="$evidence_dir/first.apk"
cp app/build/outputs/apk/release/app-release.apk "$first_apk"
first_hash=$(sha256sum "$first_apk" | cut -d' ' -f1)

build_release
second_apk=app/build/outputs/apk/release/app-release.apk
second_hash=$(sha256sum "$second_apk" | cut -d' ' -f1)

if ! cmp -s "$first_apk" "$second_apk"; then
  echo "release reproducibility check failed" >&2
  echo "first:  $first_hash" >&2
  echo "second: $second_hash" >&2
  exit 1
fi

echo "release reproducibility verified: $second_hash"
