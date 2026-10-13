# Release Playbook

Issue coverage: CM-RELEASE-001, CM-RELEASE-002, CM-REL-006 (rollback).

## Goals

1. Reproducible builds: same source + toolchain produce identical APKs.
2. SBOM + license report attached to every tagged release.
3. Signed release with a documented rollback path.

## One-time setup

```bash
# Install the exact JDK used by CI
sdk install java 17-tem

# Install the Android SDK version pinned in app/build.gradle (compileSdk 36, minSdk 34)
# The supported build matrix is AGP 9.3.0 + Gradle 9.5.0 + JDK 17.

# Configure all four `CM_RELEASE_*` properties in `~/.gradle/gradle.properties`, then verify:
./gradlew clean lintRelease testReleaseUnitTest assembleRelease
scripts/verify-release-signing.sh app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

The approved release certificate SHA-256 fingerprint is stored in
`security/release-signing-cert.sha256`. The private key is never committed. Repository Actions
secrets contain the CI copy; the permission-restricted maintainer copy is retained separately.

For automatic signed releases in this repository, configure the four `CM_RELEASE_*`
GitHub Actions secrets and set the repository variable `CM_RELEASE_SIGNING_ENABLED` to `true`.
The workflow remains disabled until a signing identity is configured; it never falls back to a debug key.

## Every release

1. **Preflight**: run lint, unit tests, the API 34/36 instrumentation matrix, security scans,
   and, unless the release owner explicitly scopes validation to emulators,
   `docs/runbooks/two-device-release-smoke.md` against the exact release candidate. Record any
   skipped physical-device coverage in the release notes.
2. **Tag**: create an annotated `v<versionName>` tag only on the tested `main` commit.
3. **Build & sign**: the tag workflow restores the private key from repository secrets and fails
   if any credential is absent, the APK is unsigned/debug-signed, or the tag/version differs.
4. **Evidence**: the workflow validates and attaches the CycloneDX 1.6 SBOM, component/license
   report, OSV/MobSF/Gitleaks results, APK, and SHA-256 checksums.
5. **Smoke test**: install on at least three physical phones running Android 14 or newer
   (one child and two parents), then run the 30-minute loop (simultaneous parent audio,
   independent reconnect/renewal, talkback, PiP).
6. **Publish**: the workflow creates the GitHub Release only after every gate passes.

## Debug-key migration

Public releases through v3.3.30 used the universal Android debug certificate. v3.3.31 is the first
release signed by the private project identity. Android will not accept an ordinary in-place update
from the old debug certificate. Existing users must:

1. Export or record any settings that must be retained.
2. Uninstall the debug-signed app from both parent and child devices.
3. Install the first privately signed release and pair once again.

Uninstalling clears app-private data, including paired-child credentials. After that one migration,
all upgrades must use the certificate in `security/release-signing-cert.sha256`; QR pairing remains
durable across IP changes and ordinary upgrades. Do not replace an old release asset in place or
reuse its version/tag for differently signed bytes.

## Rollback

If a regression reaches users:
1. `gh release list` → identify last-known-good tag.
2. Download the original APK and verify its checksum and signing certificate. Never re-sign it.
3. Android normally rejects a lower `versionCode`; publish a fixed build with a higher code using
   the same private release identity, reverting the offending source changes.
4. Distribute via the usual channel. The on-device `BootReceiver` will keep
   service restart semantics intact across version swaps.
5. File a public post-mortem using `docs/runbooks/operations-24x7.md`
   escalation notes.

## Reproducible build checklist

- [ ] Toolchain: AGP 9.3.0, Gradle 9.5.0 wrapper, JDK 17 Temurin, Android SDK 36.
- [ ] `scripts/verify-reproducible-release.sh` passes locally; the tag workflow runs the same gate.
- [ ] Compare SHA-256 of the APK on two independent machines — must match. AGP SDK dependency
      metadata stays disabled because its randomized `SDKP` signing-block pair breaks this invariant.
- [ ] Archive the exact toolchain list (`gradle --version`, `sdkmanager --list`) alongside the APK.
