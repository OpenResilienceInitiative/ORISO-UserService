# UserService stability, dependency measurements and module decision

Date: 2026-07-25
Target branch: `pre-dev`

## Reproducible stability result

The historical full-suite baseline contained 4,707 tests, 28 failures, 704 errors
and 10 skipped tests. The failures were not one defect: they combined stale
Spring Security assumptions, test-database replacement, Spring Boot 4 / Jackson
3 migration gaps, incomplete external-service test doubles, stale chat migration
expectations and two production regressions.

After repairing those clusters:

| Suite | Tests | Failures | Errors | Skipped | Command |
| --- | ---: | ---: | ---: | ---: | --- |
| Unit | 3,782 | 0 | 0 | 7 | `./mvnw -Dskip.integration-tests=true test` |
| Integration + contract + E2E | 940 | 0 | 0 | 3 | `./mvnw -Dskip.unit-tests=true integration-test` |
| MariaDB schema contracts | 2 | 0 | 0 | 0 | required fresh MariaDB job |
| Redis availability contract | 1 | 0 | 0 | 0 | required Redis job |

Nineteen stale security tests were removed. They asserted that safe `GET`
requests or the explicitly CSRF-exempt public registration endpoint require a
CSRF token, which contradicts the service's security contract. No failing test
is skipped or quarantined.

`scripts/ci/run-required-integration-tests.sh` now owns the complete `*IT`
suite, requires at least 900 executed tests and checks for critical E2E reports.
The previous three-test required subset and the non-blocking legacy quarantine
were removed. The three environment-gated cases are not quarantined: Redis has
its own required service-container job, and both MariaDB cases run in a required
fresh-MariaDB job on branch, pull-request and publish workflows.

Making the MariaDB contract required exposed and repaired one real production
schema drift: `ReservedPublicSlug.active` declared the SQL default as part of
Hibernate's expected column type. The entity now expects `TINYINT`, while the
default remains correctly owned by Liquibase. A fresh database applies all 91
changesets and passes Hibernate validation.

## Dependency and call measurements

The source inventory contains 13 generated API-controller factories, six
client/configuration helpers and 71 direct `RestTemplate` call sites. The
runtime dependency set is:

| Boundary | Dependencies | Transport |
| --- | --- | --- |
| Identity | Keycloak, identity extensions | Keycloak client + HTTP |
| Chat | Matrix; Rocket.Chat only when explicitly enabled | HTTP long-poll + HTTP; optional MongoDB |
| ORISO services | Agency, Tenant, Consulting Type/Topic/Application Settings, Appointment, Message, Mail, Live | HTTP |
| State/event infrastructure | MariaDB, Redis, RabbitMQ | JDBC, Redis, AMQP |

All `RestTemplateBuilder` clients now emit:

- `userservice.outbound.http.calls`: call attempts by dependency host, method and
  coarse outcome;
- `userservice.outbound.http.latency`: latency with the same low-cardinality
  dimensions;
- `userservice.outbound.http.payload`: exact request bytes and response bytes
  when `Content-Length` is available;
- `userservice.outbound.retries`: explicitly scheduled Keycloak and Matrix
  retries by fixed dependency and operation tags.

Paths, query values, IDs and exception text are never metric tags. Spring Boot's
standard `http.client.requests` remains available as an independent cross-check.
The Java `HttpClient` used by LiveService and Keycloak's own admin-client
transport are not covered by the payload interceptor; their higher-level retry
paths are covered by the explicit retry counter. This is a known measurement
boundary, not an implied zero.

## Chatty-call reductions

- With the default `rocket-chat.enabled=false`, account and availability reads
  no longer call Rocket.Chat. Matrix-only deployments also do not create the
  Rocket.Chat MongoDB client or credential job.
- Anonymous live-chat queue visibility is topic-only and therefore avoids an
  AgencyService lookup merely to resolve consulting-type visibility.
- Appointment deletion uses one conditional database `DELETE` and its affected
  row count. It preserves the 404 contract without a read-before-delete round
  trip.

The runtime metrics above are the gate for further optimization: prioritize a
dependency only when PreDev shows high calls per request, payload volume or p95
latency. This avoids speculative batching and caches without an invalidation
model.

## Internal module boundary

The stabilized user and appointment slices follow:

```mermaid
flowchart LR
  HTTP[Web controllers and delegates]
  IN[Input ports]
  APP[Managers, facades and workflows]
  OUT[Output ports]
  ADAPTERS[Matrix, Keycloak, Rocket.Chat, repositories and generated clients]

  HTTP --> IN --> APP --> OUT --> ADAPTERS
```

The `tests/ci/test_module_boundaries.py` contract prevents those web adapters
from reverting to concrete `AccountManager`, `Messenger`, `Organizer`,
`KeycloakService` or `RocketChatService` dependencies. The appointment deletion
repair also stays behind `Organizing` and `AppointmentRepository`, so the HTTP,
application and persistence contracts can evolve independently.

## Microservice decision

Decision: keep UserService as a modular monolith for now.

The suite demonstrates extensive shared transactional behavior and the service
already coordinates at least ten synchronous service boundaries. Splitting a
module before runtime measurements would add more network calls, partial-failure
states and contract deployment sequencing while the current internal ports
already provide the needed seam.

Reconsider extraction only when PreDev telemetry supplies all of:

1. a cohesive module with no shared-database writes across its boundary;
2. independently meaningful ownership and release cadence;
3. sustained call volume or latency that cannot be solved within the process;
4. an explicit versioned contract and failure/retry policy;
5. load and E2E evidence for both sides of the proposed split.

## Load and regression use

Run a bounded load smoke against a deployed environment:

```bash
python3 tests/load/user_service_load_smoke.py \
  --base-url https://userservice.example \
  --path /actuator/health \
  --requests 500 \
  --concurrency 20 \
  --max-error-rate 0 \
  --max-p95-ms 1000
```

Protected endpoints can receive repeated `--header 'Name: value'` arguments.
The script reports request count, error rate, response bytes, throughput and
mean/p50/p95/max latency and exits non-zero when either threshold is exceeded.
Its concurrent behavior is itself exercised by the required Python CI contract.

Local proof on 2026-07-25 used a real started UserService testing process and
500 requests at concurrency 20 against `/actuator/health/liveness`: 0 failures,
0% error rate, 2,996 requests/second, 6.58 ms mean and 17.84 ms p95 (25.98 ms
max). This is a bounded smoke baseline, not a production capacity claim. The
aggregate local health group was `DOWN` because the developer RabbitMQ instance
did not accept the testing profile's credentials; liveness and readiness were
both `UP`, and the dedicated Redis contract passed independently.
