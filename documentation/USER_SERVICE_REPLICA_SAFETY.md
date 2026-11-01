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

## Scheduled workflows

Every scheduled workflow must stay correct when two instances overlap (a
rolling update or restart overlap). That does not mean every run is exclusive.
Entries with a `claim` object name their mechanism, and the CI contract checks
that the named source really contains it. The first three mechanisms below make
a run exclusive; `idempotent-statement` deliberately allows concurrent runs
because running the statement twice has the same effect as running it once:

- `scheduled-task-claim`: the class calls `ScheduledTaskClaimService`
  (`tryClaim`, `tryClaimLease` or `runIfClaimed`). A second instance sees the
  active claim and skips the run. Sweeps that run every minute use
  `runIfClaimed`: lease, work inside the lease transaction, release after
  commit. This covers the enquiry notification, anonymous and registered-only
  deactivation and deletion, account deletion, group-chat reminder, handshake
  expiry and audit purge and support-room expiry jobs, among others.
- `unique-claim-row`: a uniquely keyed row is inserted before the side effect
  (inactive-account notification fingerprint).
- `row-lock`: a pessimistic row lock serializes the work (group-chat
  deactivation, see `GROUP_CHAT_DEACTIVATION_REPLICA_SAFETY.md`).
- `idempotent-statement`: one set-based statement that is safe to run twice
  (appointment cleanup, see `APPOINTMENT_CLEANUP_REPLICA_SAFETY.md`).

A claim prevents overlap, not a later re-run, so the guarded work stays
idempotent. Group-chat reminders are additionally deduplicated per recipient
and occurrence by a unique key. Claims that are not released early (for example
`enquiry-notification`) need a duration above the worst-case run.

Entries without `claim` (for example the enquiry-rejection repair or the
group-appointment mail queue) rely on per-row claims described in their
`decision` text; those are not machine-checked.

## Current blocker groups

1. Coordinate the Matrix sync leader and cursor across replicas and deduplicate
   durable event side effects.
2. Externalize browser-login locks, notification active-view state and
   correctness-relevant reference caches, or prove a bounded alternative.
3. Run deterministic two-instance Redis, MariaDB, integration and product E2E
   scenarios without sticky sessions.
4. Query the deployed metrics and traces in SigNoz before changing the Helm
   replica ceiling.
