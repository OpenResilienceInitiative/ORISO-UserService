#!/usr/bin/env bash

set -euo pipefail

maven_wrapper="${ORISO_MAVEN_WRAPPER:-./mvnw}"
required_tests=(
  ErrorReportControllerIT
  ChatSeriesControllerAuthorizationIT
  UserServiceApplicationIT
)
test_selector="$(IFS=,; echo "${required_tests[*]}")"

"${maven_wrapper}" -B -Dtest="${test_selector}" test

report_count=0
for test_name in "${required_tests[@]}"; do
  matches=(target/surefire-reports/TEST-*."${test_name}".xml)
  if [[ ! -e "${matches[0]}" ]]; then
    echo "Required integration test produced no report: ${test_name}" >&2
    exit 1
  fi
  report_count=$((report_count + ${#matches[@]}))
done

echo "Required integration contract produced ${report_count} report(s)."
