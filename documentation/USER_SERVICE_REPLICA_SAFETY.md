# UserService replica-safety contract

UserService currently supports one application replica. A Kubernetes
`Deployment` and stateless HTTP authentication do not make the complete process
stateless: Matrix listener coordination, notification state, local reference
caches and scheduled side effects still have replica-local behavior.

The machine-readable inventory is
[`src/main/resources/replica-safety-components.json`](../src/main/resources/replica-safety-components.json).
Its CI contract discovers every production source containing a scheduled
method or a recognized process-local state primitive and requires an owner,
risk classification, decision, signal and current status. Adding a new
`@Scheduled` method, `ConcurrentHashMap`, Caffeine cache consumer or long-lived
executor without updating the inventory fails
`tests/ci/test_replica_safety_contract.py`.

The inventory reflects the Matrix-only runtime. Removed Rocket.Chat and Jitsi
implementations are not fallback options and are not catalogued as retained
state.

## Runtime signals

- `userservice.replica.configured` reports the configured application-replica
  count.
- `userservice.replica.supported.max` reports the currently supported maximum,
  which remains `1`.
- `userservice.replica.constraint.violated` becomes `1` when the configured
  count exceeds the supported maximum.
- `userservice.replica.local_state` publishes one bounded gauge per catalogued
  local-state component with `component`, `owner`, `risk` and `status`.
- `userservice.replica.local_state.components` and
  `userservice.replica.local_state.risks` aggregate only the 17 local-state
  entries; scheduled workflows are not double-counted as local state.
- `userservice.scheduler.registered` publishes every catalogued scheduler,
  including jobs whose cron interval has not elapsed.
- `userservice.scheduler.executions` counts completed executions by bounded
  task signature and `success` or `failure`.
- `userservice.scheduler.duration` measures the corresponding duration.

Scheduler registration, execution and duration use the same bounded
`Class.method()` task signature. The CI contract verifies that each catalogued
method is an actual `@Scheduled` method, so dashboards can join registration
and execution without a handwritten name map.

The metrics contain no user, tenant, room, message or credential values. The
OpenTelemetry resource already supplies the service-instance identity, so
SigNoz can group scheduler executions by task and instance without adding pod
names as custom metric tags.

These signals prove inventory presence and execution behavior; they do not
prove leader safety. Multiple replicas remain unsupported while any
correctness or duplicate-side-effect entry is marked `blocker`.

## Current blocker groups

1. Coordinate the Matrix sync leader and cursor across replicas and deduplicate
   durable event side effects.
2. Externalize browser-login locks, notification active-view state and
   correctness-relevant reference caches, or prove a bounded alternative.
3. Lease or prove idempotency for every scheduled workflow under simultaneous
   execution.
4. Run deterministic two-instance Redis, MariaDB, integration and product E2E
   scenarios without sticky sessions.
5. Query the deployed metrics and traces in SigNoz before changing the Helm
   replica ceiling.
