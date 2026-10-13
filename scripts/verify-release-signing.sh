#!/usr/bin/env bash
set -euo pipefail

apk_path=${1:-}
fingerprint_file=${2:-security/release-signing-cert.sha256}

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "usage: $0 <release.apk> [expected-certificate.sha256]" >&2
  exit 2
fi
if [[ ! -f "$fingerprint_file" ]]; then
  echo "expected certificate file not found: $fingerprint_file" >&2
  exit 2
fi

expected=$(sed -E 's/[[:space:]:]//g; s/#.*$//' "$fingerprint_file" | tr '[:upper:]' '[:lower:]' | sed -n '/^[0-9a-f]\{64\}$/p' | head -n 1)
if [[ ${#expected} -ne 64 ]]; then
  echo "expected certificate file does not contain one SHA-256 fingerprint" >&2
  exit 2
fi

if command -v apksigner >/dev/null 2>&1; then
  apksigner_bin=$(command -v apksigner)
else
  sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  if [[ -z "$sdk_root" ]]; then
    echo "apksigner not found and ANDROID_SDK_ROOT/ANDROID_HOME is unset" >&2
    exit 2
  fi
  apksigner_bin=$(find "$sdk_root/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n 1)
fi
if [[ -z "${apksigner_bin:-}" || ! -x "$apksigner_bin" ]]; then
  echo "apksigner executable not found" >&2
  exit 2
fi

certificate=$($apksigner_bin verify --verbose --print-certs "$apk_path")
# Build-tools releases vary the indentation and colon/space grouping of certificate digests. Match
# the semantic label, then keep only hexadecimal characters after it so the release gate remains
# strict about the final 64-digit SHA-256 value without depending on presentation details.
actual=$(printf '%s\n' "$certificate" |
  sed -n -E 's/^.*certificate SHA-256 digest:[[:space:]]*(.*)$/\1/Ip' |
  head -n 1 |
  tr -cd '0-9a-fA-F' |
  tr '[:upper:]' '[:lower:]')
subject=$(printf '%s\n' "$certificate" | sed -n -E 's/^.*Signer #1 certificate DN:[[:space:]]*//p' | head -n 1)

if [[ ${#actual} -ne 64 ]]; then
  echo "could not read the APK signing certificate" >&2
  printf '%s\n' "$certificate" | sed -n '/certificate/p' >&2
  exit 1
fi
if [[ "$subject" == *"CN=Android Debug"* || "$actual" == "aae17e1ba75b8d398659b0ff523c7aabf11137c4e42e454b228058c42f3a1e75" ]]; then
  echo "release rejected: APK uses the universal Android debug certificate" >&2
  exit 1
fi
if [[ "$actual" != "$expected" ]]; then
  echo "release rejected: APK certificate does not match the approved project identity" >&2
  echo "expected: $expected" >&2
  echo "actual:   $actual" >&2
  exit 1
fi

echo "release certificate verified: $actual"
