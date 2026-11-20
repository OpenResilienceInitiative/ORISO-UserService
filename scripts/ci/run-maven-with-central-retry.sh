#!/usr/bin/env bash

set -euo pipefail

log="$(mktemp)"
trap 'rm -f -- "${log}"' EXIT

if ./mvnw "$@" 2>&1 | tee "${log}"; then
  exit 0
else
  statuses=("${PIPESTATUS[@]}")
fi

test_phase_or_compile_failure='^\[(INFO|ERROR)\] +(--- (surefire|failsafe|maven-(surefire|failsafe)-plugin):|Running [[:alnum:]_.$]+[[:space:]]*$|Tests run:|COMPILATION ERROR)'
central='\(https://repo\.maven\.apache\.org/maven2/?\)'
parent_lookup="^\[(FATAL|ERROR)\].*Non-resolvable parent POM.*Could not find artifact org\.springframework\.boot:spring-boot-starter-parent:pom:[^[:space:]]+ in .* ${central}"
transfer="^\[ERROR\].*Could not transfer artifact.* from/to .* ${central}: status code: (429|502|503|504)([^[:digit:]]|$)"

# Keep normal test and compiler failures authoritative. Refresh Maven's
# cached resolution failures once when Central fails before the test phase.
if [[ "${statuses[0]}" -eq 1 && "${statuses[1]}" -eq 0 ]] &&
  ! grep -Eq "${test_phase_or_compile_failure}" "${log}" &&
  grep -Eq "${parent_lookup}|${transfer}" "${log}"; then
  echo "Maven Central resolution failed before tests; retrying once in 15s." >&2
  sleep 15
  ./mvnw -U "$@" 2>&1
  exit 0
fi

exit "${statuses[0]}"
