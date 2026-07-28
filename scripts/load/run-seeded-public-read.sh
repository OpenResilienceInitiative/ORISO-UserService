#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
userservice_port="${USERSERVICE_LOAD_PORT:-18082}"
agency_stub_port="${AGENCY_STUB_PORT:-18083}"
request_count="${USERSERVICE_LOAD_REQUESTS:-1400}"
concurrency="${USERSERVICE_LOAD_CONCURRENCY:-32}"
max_p95_ms="${USERSERVICE_LOAD_MAX_P95_MS:-1000}"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/userservice-seeded-load.XXXXXX")"
userservice_pid=""
agency_stub_pid=""

cleanup() {
  if [[ -n "${userservice_pid}" ]] && kill -0 "${userservice_pid}" 2>/dev/null; then
    kill "${userservice_pid}" 2>/dev/null || true
    wait "${userservice_pid}" 2>/dev/null || true
  fi
  if [[ -n "${agency_stub_pid}" ]] && kill -0 "${agency_stub_pid}" 2>/dev/null; then
    kill "${agency_stub_pid}" 2>/dev/null || true
    wait "${agency_stub_pid}" 2>/dev/null || true
  fi
  rm -r "${run_dir}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

python3 "${repo_root}/tests/load/seeded_agency_stub.py" \
  --port "${agency_stub_port}" \
  >"${run_dir}/agency-stub.log" 2>&1 &
agency_stub_pid="$!"

(
  cd "${repo_root}"
  SPRING_PROFILES_ACTIVE=testing \
  SERVER_PORT="${userservice_port}" \
  SPRING_DATASOURCE_URL="jdbc:h2:mem:userservice-seeded-load;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;NON_KEYWORDS=USER,VALUE,DAY" \
  SPRING_SQL_INIT_DATA_LOCATIONS="file:${repo_root}/src/test/resources/database/UserServiceDatabase.sql" \
  AGENCY_SERVICE_API_URL="http://127.0.0.1:${agency_stub_port}" \
  LOGGING_LEVEL_ROOT=WARN \
  ./mvnw -DskipTests -Dspring-boot.run.useTestClasspath=true spring-boot:run
) >"${run_dir}/userservice.log" 2>&1 &
userservice_pid="$!"

ready=false
for ((attempt = 1; attempt <= 180; attempt += 1)); do
  if curl --fail --silent \
    "http://127.0.0.1:${userservice_port}/actuator/health/liveness" \
    >/dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "${userservice_pid}" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [[ "${ready}" != true ]]; then
  echo "UserService did not become live; last startup log lines:" >&2
  tail -120 "${run_dir}/userservice.log" >&2
  exit 1
fi

python3 "${repo_root}/tests/load/user_service_load_smoke.py" \
  --base-url "http://127.0.0.1:${userservice_port}" \
  --scenario "${repo_root}/tests/load/scenarios/seeded-public-read.json" \
  --requests 14 \
  --concurrency 1 \
  --max-error-rate 0 \
  --max-p95-ms "${max_p95_ms}" \
  >/dev/null

python3 "${repo_root}/tests/load/user_service_load_smoke.py" \
  --base-url "http://127.0.0.1:${userservice_port}" \
  --scenario "${repo_root}/tests/load/scenarios/seeded-public-read.json" \
  --requests "${request_count}" \
  --concurrency "${concurrency}" \
  --max-error-rate 0 \
  --max-p95-ms "${max_p95_ms}"
