#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 SBOM_JSON POLICY_JSON OUTPUT_TSV" >&2
  exit 2
fi

sbom_path=$1
policy_path=$2
output_path=$3

jq -e --slurpfile policy "$policy_path" '
  def allcomponents:
    [(.metadata.component + {licenses: (.metadata.licenses // .metadata.component.licenses // [])})] +
    (.components // []);
  all(
    allcomponents[];
    (.purl // "") != "" and
    ((.licenses // []) | length) > 0 and
    all(
      (.licenses // [])[];
      (.license.id // .expression // .license.name // "") as $license |
      ($policy[0].allowedLicenses | index($license)) != null
    )
  )
' "$sbom_path" >/dev/null || {
  echo "SBOM contains a missing purl/license or an unapproved license" >&2
  jq -r --slurpfile policy "$policy_path" '
    def allcomponents:
      [(.metadata.component + {licenses: (.metadata.licenses // .metadata.component.licenses // [])})] +
      (.components // []);
    allcomponents[] |
    ((.licenses // []) | map(.license.id // .expression // .license.name // "UNKNOWN")) as $licenses |
    select(
      (.purl // "") == "" or
      ($licenses | length) == 0 or
      any($licenses[]; . as $license | ($policy[0].allowedLicenses | index($license)) == null)
    ) |
    [(.purl // "NO_PURL"), ($licenses | join(",") // "NO_LICENSE")] | @tsv
  ' "$sbom_path" >&2
  exit 1
}

jq -r '
  def allcomponents:
    [(.metadata.component + {licenses: (.metadata.licenses // .metadata.component.licenses // [])})] +
    (.components // []);
  (["purl", "name", "version", "licenses", "hashes"] | @tsv),
  (allcomponents[] |
    [
      .purl,
      .name,
      (.version // ""),
      ((.licenses // []) | map(.license.id // .expression // .license.name) | join(",")),
      ((.hashes // []) | map(.alg + ":" + .content) | join(","))
    ] | @tsv
  )
' "$sbom_path" > "$output_path"
