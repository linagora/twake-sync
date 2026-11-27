#!/usr/bin/env bash
# Fails when a workflow could leak the release signing key or run unpinned action code:
#  - a job that reads secrets.ANDROID_KEY* (any case), or all secrets at once (toJSON(secrets),
#    secrets[<expression>], `secrets: inherit`), must use `environment: signing`, and no
#    workflow-level env/defaults may read them (they would reach every job);
#  - every `uses:` must be a local path (./...), pinned to a full 40-hex commit SHA, or a
#    docker:// image pinned to a sha256 digest.
# Needs yq (mikefarah v4) and jq, both preinstalled on GitHub's ubuntu runners.
set -euo pipefail

dir="${1:-.github/workflows}"
secret_re='secrets[[:space:]]*(\.|\[[[:space:]]*.)android_key|tojson\(secrets\)|secrets\[[[:space:]]*[^\x27\x22\x5c[:space:]]'
status=0

shopt -s nullglob
for wf in "$dir"/*.yml "$dir"/*.yaml; do
  json=$(yq -o=json '.' "$wf")

  if jq -e --arg re "$secret_re" 'del(.jobs) | tostring | test($re; "i")' <<<"$json" >/dev/null; then
    echo "::error file=$wf::signing secrets read outside a job; move them into a job with environment: signing"
    status=1
  fi

  # command substitution (not <(...)) so that a jq error fails the script instead of passing
  jobs=$(jq -r --arg re "$secret_re" '
    .jobs // {} | to_entries[]
    | select(((.value | tostring | test($re; "i")) or (.value.secrets? == "inherit"))
             and ((.value.environment | if type == "object" then .name else . end) != "signing"))
    | .key' <<<"$json")
  while read -r job; do
    [ -n "$job" ] || continue
    echo "::error file=$wf::job '$job' reads signing secrets without environment: signing"
    status=1
  done <<<"$jobs"

  refs=$(jq -r '
    [.. | objects | .uses? | strings]
    | map(select((startswith("./") or test("@[0-9a-f]{40}$") or test("^docker://.+@sha256:[0-9a-f]{64}$")) | not))
    | .[]' <<<"$json")
  while read -r ref; do
    [ -n "$ref" ] || continue
    echo "::error file=$wf::'uses: $ref' is not pinned to a full commit SHA"
    status=1
  done <<<"$refs"
done

if [ "$status" -eq 0 ]; then
  echo "Workflow secret and pinning checks passed"
fi
exit "$status"
