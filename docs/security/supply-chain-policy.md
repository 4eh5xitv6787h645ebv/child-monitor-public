# Supply-chain security and release evidence policy

Every dependency change must pass Android lint/unit/instrumentation tests, Android-specific MobSF
static analysis, a full-history Gitleaks scan, and OSV scanning. Dependabot tracks both Gradle and
GitHub Actions weekly. Action versions are pinned to immutable commit SHAs in executable workflows;
Dependabot PRs retain the readable release tag in a comment.

GitHub Advanced Security is not licensed for this private repository, so native CodeQL upload,
secret scanning, push protection, and dependency review are unavailable. The repository therefore
uses locally enforced MobSF, Gitleaks, dependency locking, OSV, license-policy, and SBOM gates that
do not depend on that entitlement. Vulnerability alerts and automated security updates remain
enabled in the repository settings. If Advanced Security becomes available, its native features
should be enabled in addition to these portable gates, not in place of them.

The release runtime graph is dependency-locked in `app/gradle.lockfile`. CI generates a CycloneDX
1.6 JSON SBOM from that resolved graph, validates it with the official CycloneDX CLI, and requires a
package URL and approved SPDX license for the application and every component. Changes to
`security/license-policy.json` require maintainer review and a linked rationale. Unknown or
unapproved licenses fail the build.

`EPL-2.0` is approved for the unmodified Eclipse Paho MQTT client introduced by #305. Paho's
embedded `about.html` offers the binary under EPL-2.0 and Eclipse Distribution License 1.0; the
CycloneDX Maven metadata reports EPL-2.0, so that is the SPDX identifier enforced in the SBOM. This
approval does not waive source, notice, or redistribution obligations and is not blanket approval
for copying EPL source into the application.

OSV findings fail CI and releases. High or critical vulnerabilities must be fixed before release;
there is no silent allowlist. A time-limited exception requires a public Project #6 issue recording
the CVE/OSV identifier, affected reachability, compensating control, owner, and expiry, plus an
explicit rule in source control. The intentionally vulnerable Log4Shell lockfile under
`security/fixtures/` is scanned separately and CI fails if the scanner ever stops detecting it.

For each tag, the release workflow must attach:

- the signed release APK;
- CycloneDX JSON SBOM;
- tab-separated component/license/hash report;
- APK and evidence SHA-256 checksums;
- OSV, MobSF, and Gitleaks scan evidence or attestations.

Release artifacts are generated from the tag, never edited by hand. A release is blocked if any
artifact is missing, the SBOM fails schema validation, the signing certificate is unapproved, or a
security gate is red. Security alerts are triaged in Project #6 and secrets are revoked before code
cleanup if one is detected.
