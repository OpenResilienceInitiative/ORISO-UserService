#!/usr/bin/env bash

set -euo pipefail

maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../.." && pwd -P)"
maven_settings="${script_dir}/github-maven-settings.xml"

started_ns=$(python3 -c 'import time; print(time.monotonic_ns())')
revision=
dirty=
selection=
failed_step=checkout
maven_log=

report_outcome() {
  local status=$?
  trap - EXIT
  if [[ -n "${maven_log}" ]]; then
    rm -f -- "${maven_log}" || true
  fi
  if [[ "${status}" -eq 0 && -z "${revision}" ]]; then
    failed_step=checkout
    status=1
  fi
  python3 - "${started_ns}" "${revision}" "${dirty}" "${selection:-all}" "${failed_step}" "${status}" <<'PYTHON'
import json
import os
from pathlib import Path
import sys
import time

start, revision, dirty, selection, step, exit_code = sys.argv[1:]
status = int(exit_code)
report = {
    "check": "userservice-maven-integration",
    "revision": revision or None,
    "dirty": None if not dirty else dirty == "true",
    "selection": selection,
    "durationSeconds": round((time.monotonic_ns() - int(start)) / 1e9, 3),
    "outcome": "failed" if status else "passed",
    "failedStep": step if status else None,
    "exitCode": status,
}
serialized = json.dumps(report)
print(serialized)
if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
    with Path(summary).open("a", encoding="utf-8") as stream:
        stream.write(f"### UserService integration verification\n\n```json\n{serialized}\n```\n")
PYTHON
  exit "${status}"
}
trap report_outcome EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -z "${ORISO_MAVEN_WRAPPER:-}" && "$(pwd -P)" != "${repository_root}" ]]; then
  echo "Run this script from the UserService checkout that contains it." >&2
  exit 1
fi
if revision=$(git rev-parse --verify HEAD 2>/dev/null); then
  checkout_state=$(git status --porcelain --untracked-files=normal)
  dirty=false
  if [[ -n "${checkout_state}" ]]; then dirty=true; fi
fi
if [[ -n "${VERIFICATION_SOURCE_SHA:-}" ]] &&
    [[ "${revision}" != "${VERIFICATION_SOURCE_SHA}" || "${dirty}" != false ]]; then
  echo "The required integration contract needs the clean GitHub candidate." >&2
  exit 1
fi

failed_step=test-selection
if [[ "$#" -ne 0 ]]; then
  if [[ "$#" -ne 2 || "$1" != --test || ! "$2" =~ ^[A-Za-z][A-Za-z0-9_]*IT$ ]]; then
    selection=invalid
    echo "Usage: scripts/ci/run-required-integration-tests.sh [--test NamedTestIT]" >&2
    exit 2
  fi
  selection="$2"
  if [[ -n "${VERIFICATION_SOURCE_SHA:-}" ]]; then
    echo "The required GitHub integration job runs the full H2 assignment." >&2
    exit 2
  fi
fi

run_maven() {
  local max_attempts="${ORISO_MAVEN_RESOLVE_ATTEMPTS:-4}"
  local delay="${ORISO_MAVEN_RESOLVE_RETRY_DELAY_SECONDS:-15}"
  local attempt=1
  local status
  maven_log="$(mktemp)"
  while true; do
    set +e
    set +o pipefail
    "${maven_wrapper}" -s "${maven_settings}" "$@" 2>&1 | tee "${maven_log}"
    status=${PIPESTATUS[0]}
    set -o pipefail
    set -e
    if [[ "${status}" -eq 0 ]]; then
      return 0
    fi
    if [[ "${attempt}" -ge "${max_attempts}" ]] ||
      ! grep -Eq 'status code: 403|Could not transfer artifact' "${maven_log}"; then
      return "${status}"
    fi
    echo "Maven Central transfer failed (attempt ${attempt}/${max_attempts}); retrying in ${delay}s." >&2
    sleep "${delay}"
    attempt=$((attempt + 1))
    if [[ "${delay}" -gt 0 ]]; then
      delay=$((delay * 2))
    fi
  done
}

# The native jobs use the same assignment to select these tests.
required_test_pattern="$(python3 "${script_dir}/database-test-assignment.py" h2)"

if [[ -n "${selection}" ]]; then
  if [[ ",${required_test_pattern}," == *",!${selection},"* ]]; then
    echo "${selection} belongs to the MariaDB assignment." >&2
    exit 2
  fi
  if [[ -z "$(find src/test/java -type f -name "${selection}.java" -print -quit)" ]]; then
    echo "No local integration test named ${selection}." >&2
    exit 2
  fi
  required_test_pattern="${selection}"
  failed_step=integration-environment
  while IFS= read -r name; do
    case "${name}" in
      LIQUIBASE_IT_DB_*|SPRING_DATASOURCE_*|SPRING_LIQUIBASE_*|SPRING_CONFIG_*|SPRING_PROFILES_*|SPRING_APPLICATION_JSON)
        if [[ -n "${!name}" ]]; then
          echo "Unset ${name} before running a targeted H2 test." >&2
          exit 2
        fi
        ;;
    esac
  done < <(compgen -e)
fi

# Both modes clean target/. Hold the lock outside it until report validation finishes.
if [[ -n "${revision}" ]]; then
  failed_step=checkout-busy
  exec 9>"$(git rev-parse --git-path oriso-integration-verification.lock)"
  if ! python3 -c 'import fcntl; fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)' 2>/dev/null; then
    echo "Integration verification could not lock this checkout. Use a separate worktree or wait." >&2
    exit 1
  fi
fi

# The application/H2 *IT suite is the required contract here. Unit tests and real-MariaDB tests
# have their own required jobs, so they are not discovered in this report inventory.
report_started_ns=$(python3 -c 'import time; print(time.time_ns())')
failed_step=maven-integration
run_maven -B -Dskip.unit-tests=true "-Dtest=${required_test_pattern}" clean integration-test

failed_step=integration-report
python3 - "${selection}" "${report_started_ns}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

selection, epoch_ns = sys.argv[1:]
pattern = "TEST-*.xml" if selection else "TEST-*IT.xml"
reports = sorted(Path("target/surefire-reports").glob(pattern))
if any(report.stat().st_mtime_ns < int(epoch_ns) for report in reports):
    sys.exit("Integration reports predate the current Maven invocation.")
# The complete Matrix-only suite produces at least 75 reports / 830 tests. Keep these
# bounds explicit so Maven cannot silently skip a material part of the suite. The
# previous 900-test floor included deleted Rocket.Chat-only tests.
minimum_reports = 75
minimum_tests = 830
required_e2e = {
    "ConversationControllerAuthorizationIT",
    "ConversationControllerIT",
    "UserAdminControllerE2EIT",
    "UserControllerE2EIT",
    "MatrixBrowserDeviceSynapseIT",
}

if selection:
    minimum_reports = minimum_tests = 1
    required_e2e = {selection}
    if len(reports) != 1:
        sys.exit(f"Expected one fresh report for {selection}, found {len(reports)}.")

tests = failures = errors = skipped = 0
classes = set()
for report in reports:
    root = ET.parse(report).getroot()
    suite_class = root.attrib.get("name", "").rsplit(".", 1)[-1]
    if selection:
        cases = root.findall("testcase")
        case_classes = {case.get("classname", "").rsplit(".", 1)[-1] for case in cases}
        if root.tag != "testsuite" or suite_class != selection or not cases or case_classes != {selection}:
            sys.exit(f"Expected executed cases from {selection} in {report.name}.")
        observed = {"tests": len(cases)}
        for attribute, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            observed[attribute] = sum(case.find(tag) is not None for case in cases)
        if any(root.get(attribute) != str(count) for attribute, count in observed.items()):
            sys.exit(f"Inconsistent case counts in {report.name}.")
    tests += int(root.attrib.get("tests", 0))
    failures += int(root.attrib.get("failures", 0))
    errors += int(root.attrib.get("errors", 0))
    skipped += int(root.attrib.get("skipped", 0))
    classes.add(suite_class)

executed = tests - skipped
missing_e2e = sorted(required_e2e - classes)
print(
    "Required integration contract: "
    f"reports={len(reports)} tests={tests} executed={executed} failures={failures} "
    f"errors={errors} skipped={skipped}"
)
contract_failed = False
if len(reports) < minimum_reports:
    print(
        f"Expected at least {minimum_reports} integration reports, found {len(reports)}.",
        file=sys.stderr,
    )
    contract_failed = True
if executed < minimum_tests:
    print(
        f"Expected at least {minimum_tests} executed integration tests, found {executed}.",
        file=sys.stderr,
    )
    contract_failed = True
if skipped:
    print(f"Integration reports contain {skipped} skipped tests.", file=sys.stderr)
    contract_failed = True
if failures or errors:
    print("Integration reports contain failures or errors.", file=sys.stderr)
    contract_failed = True
if missing_e2e:
    print(f"Missing critical E2E reports: {', '.join(missing_e2e)}", file=sys.stderr)
    contract_failed = True
if contract_failed:
    sys.exit(1)
PY
