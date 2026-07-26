#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${repo_root}/scripts/load/ensure-java-21.sh"
ensure_java_21

replica_one_port="${USERSERVICE_REPLICA_ONE_PORT:-18082}"
replica_two_port="${USERSERVICE_REPLICA_TWO_PORT:-18084}"
agency_stub_port="${AGENCY_STUB_PORT:-18083}"
request_count="${USERSERVICE_LOAD_REQUESTS:-1400}"
concurrency="${USERSERVICE_LOAD_CONCURRENCY:-32}"
max_p95_ms="${USERSERVICE_LOAD_MAX_P95_MS:-1000}"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/userservice-replica-load.XXXXXX")"
run_id="userservice-replica-load-$$"
mariadb_container="${run_id}-mariadb"
redis_container="${run_id}-redis"
userservice_replica_one_pid=""
userservice_replica_two_pid=""
agency_stub_pid=""

stop_process() {
  local process_id="$1"
  if [[ -n "${process_id}" ]] && kill -0 "${process_id}" 2>/dev/null; then
    kill "${process_id}" 2>/dev/null || true
    wait "${process_id}" 2>/dev/null || true
  fi
}

cleanup() {
  stop_process "${userservice_replica_one_pid}"
  stop_process "${userservice_replica_two_pid}"
  stop_process "${agency_stub_pid}"
  docker rm -f "${mariadb_container}" >/dev/null 2>&1 || true
  docker rm -f "${redis_container}" >/dev/null 2>&1 || true
  rm -r "${run_dir}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in docker curl python3 java; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is unavailable: ${command}" >&2
    exit 1
  fi
done

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

python3 "${repo_root}/tests/load/seeded_agency_stub.py" \
  --port "${agency_stub_port}" \
  >"${run_dir}/agency-stub.log" 2>&1 &
agency_stub_pid="$!"

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
  AGENCY_SERVICE_API_URL="http://127.0.0.1:${agency_stub_port}" \
  MATRIX_EVENT_LISTENER_ENABLED=false \
  ROCKET_CHAT_ENABLED=false \
  SPRING_TASK_SCHEDULING_ENABLED=false \
  LOGGING_LEVEL_ROOT=WARN \
  java -jar "${jar_path}" >"${log_file}" 2>&1 &
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
    tail -120 "${log_file}" >&2
    exit 1
  fi
}

start_replica "${replica_one_port}" "${run_dir}/userservice-replica-one.log"
userservice_replica_one_pid="$!"
wait_for_replica \
  "${replica_one_port}" \
  "${userservice_replica_one_pid}" \
  "${run_dir}/userservice-replica-one.log"

docker exec --interactive \
  "${mariadb_container}" \
  mariadb --user=root --password=root userservice \
  <"${repo_root}/src/test/resources/database/UserServiceDatabase.sql"

start_replica "${replica_two_port}" "${run_dir}/userservice-replica-two.log"
userservice_replica_two_pid="$!"
wait_for_replica \
  "${replica_two_port}" \
  "${userservice_replica_two_pid}" \
  "${run_dir}/userservice-replica-two.log"

python3 "${repo_root}/tests/load/user_service_load_smoke.py" \
  --base-url "http://127.0.0.1:${replica_one_port}" \
  --replica-url "http://127.0.0.1:${replica_two_port}" \
  --scenario "${repo_root}/tests/load/scenarios/seeded-public-read.json" \
  --requests 14 \
  --concurrency 2 \
  --max-error-rate 0 \
  --max-p95-ms "${max_p95_ms}" \
  >/dev/null

python3 "${repo_root}/tests/load/user_service_load_smoke.py" \
  --base-url "http://127.0.0.1:${replica_one_port}" \
  --replica-url "http://127.0.0.1:${replica_two_port}" \
  --scenario "${repo_root}/tests/load/scenarios/seeded-public-read.json" \
  --requests "${request_count}" \
  --concurrency "${concurrency}" \
  --max-error-rate 0 \
  --max-p95-ms "${max_p95_ms}"
