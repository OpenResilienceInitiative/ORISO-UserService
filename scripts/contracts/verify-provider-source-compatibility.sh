#!/usr/bin/env bash

set -euo pipefail

base_repo="${1:?base repository root is required}"
head_dir="${2:?head bundle directory is required}"
oasdiff_bin="${OASDIFF_BIN:-oasdiff}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Reviewed lists of contracts and breaking changes that were removed on purpose.
retired_contracts="${RETIRED_PROVIDER_CONTRACTS:-${script_dir}/retired-provider-contracts.txt}"
accepted_changes="${ACCEPTED_BREAKING_CHANGES:-${script_dir}/accepted-breaking-changes.txt}"

base_repo="$(cd "${base_repo}" && pwd)"
head_dir="$(cd "${head_dir}" && pwd)"

# Prints the reason when the contract is listed as retired; fails otherwise.
retired_reason() {
  local name="$1" entry reason
  [[ -f "${retired_contracts}" ]] || return 1
  while IFS= read -r entry; do
    [[ -z "${entry// /}" || "${entry}" == \#* ]] && continue
    if [[ "${entry%% *}" == "${name}" ]]; then
      reason="${entry#"${name}"}"
      reason="${reason#"${reason%%[! ]*}"}"
      [[ -n "${reason}" ]] || return 1
      echo "${reason}"
      return 0
    fi
  done < "${retired_contracts}"
  return 1
}

ignore_args=()
if [[ -f "${accepted_changes}" ]]; then
  ignore_args=(--err-ignore "${accepted_changes}" --warn-ignore "${accepted_changes}")
fi

checked=0
retired=0
while IFS= read -r base_spec; do
  name="$(basename "${base_spec}")"
  head_spec="${head_dir}/${name}"
  if [[ ! -f "${head_spec}" ]]; then
    if reason="$(retired_reason "${name}")"; then
      echo "Provider contract retired on purpose: ${name} (${reason})"
      retired=$((retired + 1))
      continue
    fi
    echo "Provider contract was removed: ${name}" >&2
    exit 1
  fi
  "${oasdiff_bin}" breaking "${base_spec}" "${head_spec}" \
    --flatten-allof \
    --fail-on WARN \
    --format githubactions \
    ${ignore_args[@]+"${ignore_args[@]}"}
  checked=$((checked + 1))
done < <(find "${base_repo}/api" -maxdepth 1 -type f \( -name '*.yaml' -o -name '*.yml' \) | sort)

if [[ "${checked}" -eq 0 ]]; then
  echo "No base provider contracts were available for compatibility checking." >&2
  exit 1
fi

echo "Verified ${checked} provider source contract(s) against the bundled pull-request head (${retired} retired on purpose)."
