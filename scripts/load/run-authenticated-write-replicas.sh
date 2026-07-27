#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${repo_root}/scripts/load/ensure-java-21.sh"
ensure_java_21

request_count="${USERSERVICE_LOAD_REQUESTS:-200}"
restart_request_count="${USERSERVICE_RESTART_REQUESTS:-12}"
concurrency="${USERSERVICE_LOAD_CONCURRENCY:-16}"
max_p95_ms="${USERSERVICE_LOAD_MAX_P95_MS:-1000}"
keep_run_dir="${USERSERVICE_KEEP_RUN_DIR:-false}"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/userservice-authenticated-replicas.XXXXXX")"
run_id="userservice-authenticated-replicas-$$"
mariadb_container="${run_id}-mariadb"
redis_container="${run_id}-redis"
replica_one_pid=""
replica_two_pid=""
jwk_stub_pid=""
started_replica_pid=""

allocate_port() {
  python3 -c \
    'import socket; sock = socket.socket(); sock.bind(("127.0.0.1", 0)); print(sock.getsockname()[1]); sock.close()'
}

stop_process() {
  local process_id="$1"
  if [[ -n "${process_id}" ]] && kill -0 "${process_id}" 2>/dev/null; then
    kill "${process_id}" 2>/dev/null || true
    wait "${process_id}" 2>/dev/null || true
  fi
}

cleanup() {
  local exit_status="$?"
  stop_process "${replica_one_pid}"
  stop_process "${replica_two_pid}"
  stop_process "${jwk_stub_pid}"
  docker rm -f "${mariadb_container}" >/dev/null 2>&1 || true
  docker rm -f "${redis_container}" >/dev/null 2>&1 || true
  if [[ "${keep_run_dir}" == "true" && "${exit_status}" != "0" ]]; then
    echo "Preserved failed replica proof artifacts: ${run_dir}" >&2
  else
    rm -r "${run_dir}"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in docker curl grep openssl python3 java; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is unavailable: ${command}" >&2
    exit 1
  fi
done

replica_one_port="$(allocate_port)"
replica_two_port="$(allocate_port)"
jwk_stub_port="$(allocate_port)"

docker run --detach \
  --name "${mariadb_container}" \
  --env MARIADB_ROOT_PASSWORD=root \
  --env MARIADB_DATABASE=userservice \
  --publish 127.0.0.1::3306 \
  --health-cmd "healthcheck.sh --connect --innodb_initialized" \
  --health-interval 2s \
  --health-timeout 5s \
  --health-retries 30 \
  mariadb:11.0.6 \
  >"${run_dir}/mariadb.container"

docker run --detach \
  --name "${redis_container}" \
  --publish 127.0.0.1::6379 \
  --health-cmd "redis-cli ping" \
  --health-interval 2s \
  --health-timeout 3s \
  --health-retries 30 \
  redis:7-alpine \
  >"${run_dir}/redis.container"

for container in "${mariadb_container}" "${redis_container}"; do
  healthy=false
  for ((attempt = 1; attempt <= 90; attempt += 1)); do
    if [[ "$(docker inspect --format '{{.State.Health.Status}}' "${container}")" == "healthy" ]]; then
      healthy=true
      break
    fi
    sleep 1
  done
  if [[ "${healthy}" != true ]]; then
    echo "Container did not become healthy: ${container}" >&2
    docker logs --tail 120 "${container}" >&2
    exit 1
  fi
done

mariadb_binding="$(docker port "${mariadb_container}" 3306/tcp | head -n 1)"
redis_binding="$(docker port "${redis_container}" 6379/tcp | head -n 1)"
mariadb_port="${mariadb_binding##*:}"
redis_port="${redis_binding##*:}"
jdbc_url="jdbc:mariadb://127.0.0.1:${mariadb_port}/userservice"
identity_url="http://127.0.0.1:${jwk_stub_port}/auth/realms/testing/.well-known/openid-configuration"

python3 "${repo_root}/tests/load/jwt_jwk_stub.py" \
  --port "${jwk_stub_port}" \
  --private-key "${run_dir}/jwt-private-key.pem" \
  --token-file "${run_dir}/consultant.jwt" \
  >"${run_dir}/jwk-stub.log" 2>&1 &
jwk_stub_pid="$!"

jwk_ready=false
for ((attempt = 1; attempt <= 30; attempt += 1)); do
  if curl --fail --silent "http://127.0.0.1:${jwk_stub_port}/health" >/dev/null; then
    jwk_ready=true
    break
  fi
  if ! kill -0 "${jwk_stub_pid}" 2>/dev/null; then
    break
  fi
  sleep 1
done
if [[ "${jwk_ready}" != true ]]; then
  echo "Disposable JWK endpoint did not become ready" >&2
  cat "${run_dir}/jwk-stub.log" >&2
  exit 1
fi

"${repo_root}/mvnw" -B -DskipTests -Dskip.unit-tests=true -Dskip.integration-tests=true package \
  >"${run_dir}/package.log" 2>&1
jar_path="${repo_root}/target/UserService.jar"
if [[ ! -f "${jar_path}" ]]; then
  echo "Packaged UserService jar is missing: ${jar_path}" >&2
  tail -120 "${run_dir}/package.log" >&2
  exit 1
fi

start_replica() {
  local port="$1"
  local log_file="$2"
  env \
    SPRING_PROFILES_ACTIVE=testing \
    SERVER_PORT="${port}" \
    SPRING_DATASOURCE_URL="${jdbc_url}" \
    SPRING_DATASOURCE_USERNAME=root \
    SPRING_DATASOURCE_PASSWORD=root \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.mariadb.jdbc.Driver \
    SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.MariaDBDialect \
    SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
    SPRING_LIQUIBASE_ENABLED=true \
    SPRING_SQL_INIT_MODE=never \
    SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=false \
    SPRING_DATA_REDIS_HOST=127.0.0.1 \
    SPRING_DATA_REDIS_PORT="${redis_port}" \
    IDENTITY_OPENID_CONNECT_URL="${identity_url}" \
    KEYCLOAK_AUTH_SERVER_URL="http://127.0.0.1:${jwk_stub_port}/auth" \
    MATRIX_EVENT_LISTENER_ENABLED=false \
    ROCKET_CHAT_ENABLED=false \
    SPRING_TASK_SCHEDULING_ENABLED=false \
    LOGGING_LEVEL_ROOT=WARN \
    java -jar "${jar_path}" >"${log_file}" 2>&1 &
  started_replica_pid="$!"
}

wait_for_replica() {
  local port="$1"
  local process_id="$2"
  local log_file="$3"
  local ready=false
  for ((attempt = 1; attempt <= 180; attempt += 1)); do
    if curl --fail --silent \
      "http://127.0.0.1:${port}/actuator/health/liveness" \
      >/dev/null; then
      ready=true
      break
    fi
    if ! kill -0 "${process_id}" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != true ]]; then
    echo "UserService replica on port ${port} did not become live:" >&2
    tail -160 "${log_file}" >&2
    exit 1
  fi
}

start_replica "${replica_one_port}" "${run_dir}/replica-one.log"
replica_one_pid="${started_replica_pid}"
wait_for_replica "${replica_one_port}" "${replica_one_pid}" "${run_dir}/replica-one.log"

start_replica "${replica_two_port}" "${run_dir}/replica-two.log"
replica_two_pid="${started_replica_pid}"
wait_for_replica "${replica_two_port}" "${replica_two_pid}" "${run_dir}/replica-two.log"

# Warm the JWT/JWK, security, controller, transaction and SQL paths on a separate scope. The
# measured version-1 scope remains absent, so the main phase still exercises its first-write race.
python3 "${repo_root}/tests/load/authenticated_tutorial_replica_load.py" \
  --replica-url "http://127.0.0.1:${replica_one_port}" \
  --replica-url "http://127.0.0.1:${replica_two_port}" \
  --token-file "${run_dir}/consultant.jwt" \
  --tour-version 999 \
  --requests 12 \
  --concurrency 2 \
  --max-p95-ms 5000 \
  >/dev/null

python3 "${repo_root}/tests/load/authenticated_tutorial_replica_load.py" \
  --replica-url "http://127.0.0.1:${replica_one_port}" \
  --replica-url "http://127.0.0.1:${replica_two_port}" \
  --token-file "${run_dir}/consultant.jwt" \
  --requests "${request_count}" \
  --concurrency "${concurrency}" \
  --max-p95-ms "${max_p95_ms}" \
  | tee "${run_dir}/initial-report.json"

stop_process "${replica_one_pid}"
replica_one_pid=""
start_replica "${replica_one_port}" "${run_dir}/replica-one-restarted.log"
replica_one_pid="${started_replica_pid}"
wait_for_replica \
  "${replica_one_port}" \
  "${replica_one_pid}" \
  "${run_dir}/replica-one-restarted.log"

python3 "${repo_root}/tests/load/authenticated_tutorial_replica_load.py" \
  --replica-url "http://127.0.0.1:${replica_one_port}" \
  --replica-url "http://127.0.0.1:${replica_two_port}" \
  --token-file "${run_dir}/consultant.jwt" \
  --requests "${restart_request_count}" \
  --concurrency 2 \
  --max-p95-ms "${max_p95_ms}" \
  | tee "${run_dir}/restart-report.json"

canonical_rows="$(
  docker exec "${mariadb_container}" \
    mariadb --batch --skip-column-names --user=root --password=root userservice \
    --execute \
    "SELECT COUNT(*) FROM tutorial_progress WHERE user_id='tutorial-replica-jwt-user' AND surface='frontend' AND tour_id='consultant-walkthrough' AND tour_version=1;"
)"
if [[ "${canonical_rows}" != "1" ]]; then
  echo "Expected one canonical tutorial progress row, found ${canonical_rows}" >&2
  exit 1
fi

if grep -En "Duplicate entry.*uq_tutorial_progress_scope" \
  "${run_dir}/replica-one.log" \
  "${run_dir}/replica-two.log" \
  "${run_dir}/replica-one-restarted.log"; then
  echo "Concurrent authenticated writes emitted a duplicate-key warning" >&2
  exit 1
fi

echo "Authenticated two-replica write proof passed with one canonical row after restart."
