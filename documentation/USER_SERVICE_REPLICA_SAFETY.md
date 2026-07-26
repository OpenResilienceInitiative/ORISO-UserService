# UserService replica-safety contract

UserService is currently safe to deploy with one replica. A Kubernetes
`Deployment` and stateless HTTP security do not make the whole process
stateless: the legacy Rocket.Chat Ehcache entry and provider-gated scheduled
side effects still prevent a global multi-replica claim. Authentication tokens,
reference-data and correctness-relevant tenant/application-setting reads,
notification active-view state and Matrix sync leadership/cursor state are now
externalized as described below, but the remaining items still keep the global
replica limit at one.

The machine-readable inventory is
[`src/main/resources/replica-safety-components.json`](../src/main/resources/replica-safety-components.json).
Its executable contract discovers every production source containing a
scheduled method or a recognized process-local state primitive and requires an
owner, risk classification, decision and signal. Adding a new scheduled method,
`ConcurrentHashMap`, local Ehcache user or long-lived executor without updating
the inventory fails `tests/ci/test_replica_safety_contract.py`.

## Runtime signals

- `userservice.replica.max_supported` is `1` until every correctness-relevant
  item is externalized or receives deterministic multi-instance proof.
- `userservice.replica.local_state` is a bounded gauge per checked-in component
  with only `component`, `owner` and `risk` tags. It contains no user, tenant,
  room, token or message identifiers.
- `userservice.scheduler.registered` publishes every catalogued scheduler at
  startup, including jobs whose cron interval has not elapsed yet.
- `userservice.scheduler.executions` counts every completed scheduled execution
  by bounded task name and `success` or `failure`.
- `userservice.scheduler.duration` measures the corresponding duration.
- `userservice.notification.active_view.store.operations` records only bounded
  Redis operation/outcome tags for active-view writes, reads and deletes.
- `userservice.matrix.sync.coordination.operations` records only bounded Redis
  lease/cursor operation and outcome tags; it never contains owner, room,
  token or event identifiers.
- `userservice.matrix.browser_login.coordination.operations` records only
  bounded acquire/release operation and outcome tags. Matrix identities,
  device IDs, lock keys and owner tokens are never metric tags.
- `userservice.shared_read_cache.operations` records bounded cache, operation
  and outcome tags for tenant, tenant-admin, application-setting, agency,
  consulting-type and topic reads. It never contains tenant IDs, subdomains or
  cache keys.

SigNoz already receives `service.instance.id` as a resource attribute. Grouping
`userservice.scheduler.executions` by task and service instance makes duplicate
multi-replica execution visible without adding pod names as metric tags.

These signals prove inventory presence and execution behavior; they do not
prove a scheduler is leader-safe. The deployment must remain at one replica
until the catalog decision for every correctness or duplicate-side-effect item
is resolved and a two-instance integration suite passes without sticky
sessions.

The first resolved scheduler contract is the group-chat reminder:
`EventNotificationServiceReplicaIT` starts two independent service instances
against the same database and releases them concurrently with the same
recipient/deduplication key. The JPA model and MariaDB migration now express the
same unique constraint. Exactly one event row and one live refresh are
observable after the race.

The appointment cleanup needs no distributed claim because its complete effect
is one native database delete over a fixed clock cutoff and it has no external
side effects. `OrganizerMariaDbReplicaIT` releases two cleanup executions
concurrently against MariaDB 11.0.6 with 120 expired and 30 current
appointments. Both transactions complete successfully and exactly the 30
current rows remain. The reusable MariaDB CI contract runs this proof together
with schema-drift and statistics-repository validation on MariaDB 10.11.

The daily inactive-session deletion scheduler has database and external
provider side effects, so it uses the durable `inactive-session-deletion` claim
with a 12-hour bound. `DeleteInactiveSessionsAndUserSchedulerReplicaIT`
releases two scheduler instances concurrently against the same claim store and
proves that only one instance sets tenant context or enters the destructive
workflow. The losing replica performs no database, Keycloak, Matrix or legacy
provider cleanup work.

The one-minute group-chat deactivation scheduler also needs no coarse global
claim. Its transactional active-chat selection takes a pessimistic database
row lock, so concurrent instances serialize each active chat while its database
transition and Matrix shutdown are in progress.
`DeactivateGroupChatSchedulerMariaDbReplicaIT` starts two scheduler instances
against MariaDB 11.0.6 and one expired Matrix chat. Both instances enter the
workflow, but the chat is deleted once and its Matrix room is purged exactly
once. This per-chat coordination keeps the one-minute schedule intact and
remains part of the Matrix-only target after the separate Rocket.Chat branch is
physically removed.

Notification active-view suppression is no longer process-local.
`ActiveViewRegistry` stores one encoded room/thread value per user in Redis with
a 30-second TTL. The frontend refreshes this state every ten seconds; a lost
inactive request can therefore suppress notifications only until the TTL
expires. Redis reads fail open, so a store outage can make notifications noisy
but cannot make them disappear. `ActiveViewRegistryRedisIT` reconstructs a
second registry over the same Redis 7 store and proves shared reads, immediate
clear and expiry. The reusable Redis workflow runs this contract together with
consultant availability before PR, branch and publish workflows can succeed.

Tenant, tenant-admin, application-setting, agency, consulting-type and topic
reads are no longer stored in replica-local Ehcache entries. `SharedReadCache`
stores them in Redis under tenant-aware or lookup-specific keys. The TTL must
be between one and 60 seconds; a larger or unbounded deployment override fails
at startup. A 15-second owner token coordinates a cold load across replicas,
with owner-verified release. Contenders wait at most 14 seconds, just above the
normal three-second connect plus ten-second read timeout, and then load
directly so a lost lock cannot block a request indefinitely. Redis read, lock
or write failures fail open to the authoritative upstream service. Tenant
fresh reads also replace both ID and subdomain entries immediately.
`SharedReadCacheRedisIT` reconstructs two cache instances against Redis 7 and
proves shared reads, bounded expiry/reload, one upstream load for a concurrent
cold miss, reference-list serialization and tenant isolation. Reference reads
therefore have one shared freshness bound instead of the previous replica-local
60-second, three-hour and 24-hour windows.

Magic-login and password-reset tokens no longer live in replica-local maps.
Both flows depend on the `OneTimeTokenStore` port, whose Redis adapter uses
TTL-bound values and an atomic remove-first claim. A token created by one
application instance can be consumed exactly once by another. Password reset
also maintains a hashed subject index, so creating a new token invalidates the
older token across replicas. Both bearer tokens and account identifiers are
SHA-256-derived before they enter Redis keys. Redis failures fail closed; there
is no heap fallback. Failed downstream operations restore a claim only for its
original remaining TTL and only when a newer subject token does not already own
the index.
`RedisOneTimeTokenStoreIT` reconstructs two adapters over the same Redis 7
instance and proves cross-instance claim, replacement, expiry and stale-claim
protection. The reusable Redis workflow runs this contract for branch and PR
validation.

Matrix browser-device login no longer relies on a process-local lock. Password
rotation and consumption now run inside `MatrixBrowserLoginCoordinator`, which
uses a Redis `SET NX` lease keyed by a SHA-256 digest of the Matrix identity.
The raw identity is not stored in the key. Each attempt has an opaque owner
token, and a Lua compare-and-delete releases only the current owner's lease.
Acquisition failures and the ten-second contention limit fail closed; release
failures leave the 30-second TTL as the recovery bound.
`MatrixBrowserLoginCoordinatorRedisIT` reconstructs two coordinator instances
against Redis 7 and proves mutual exclusion plus stale-owner recovery after
expiry. The protected operation makes two normal Matrix HTTP calls. Their
configured three-second connection and ten-second read limits keep the
worst-case sequential network wait below the 30-second lease. Deployment
overrides must preserve that inequality, and
`userservice.matrix.browser_login.coordination.operations` must be monitored
for contention, timeouts and release failures.

Matrix `/sync` leadership and cursor state are no longer process-local.
`MatrixSyncCoordinationRegistry` gives one logical consumer a short Redis lease,
loads a shared cursor after acquisition and atomically commits the next cursor
only while the same owner still holds the lease. A listener renews before and
after the 30-second long poll and processes a batch synchronously before
committing its cursor. Persisted feed notifications and consultant-message
statistics use opaque Matrix-event identity keys, so a crash between a durable
effect and cursor commit is replay-safe. A non-duplicate failure from either
durable sink prevents the cursor commit. `MatrixSyncCoordinationRegistryRedisIT`
proves exclusive acquisition, owner-only release, stale-owner rejection and
cursor handover across two registry instances against Redis 7. Room context is
read from the canonical session repository once per joined room and sync batch.
This refresh overwrites the local room-to-session/recipient scratch entries
before any event is processed, so assignments changed by another replica are
visible on the next batch. The scratch entries are cleared in a `finally` block
after every room, including failed batches, so they cannot grow with historical
rooms. The HTTP register/unregister contract no longer mutates listener-local
state. Tests prove fresh context across successive batches, one repository
lookup for multiple events in the same batch and post-batch scratch cleanup.

The inactive-account notification proof starts two independent service
instances against the same audit database. A transaction-isolated unique claim
is committed before the external mail call, so the losing instance performs no
mail call and no longer needs a separate existence query. MailService reports
whether it accepted the request; only accepted requests set
`emailDispatched=true`, while rejected requests remain auditable as
undispatched. Every attempt now carries a deterministic opaque
`Idempotency-Key`, while the persisted audit row owns the recipient and message
payload used for all replays. A pessimistic row lock records the attempt start
and count before the external call, preventing normal concurrent dispatch.
Audit rows created before this protocol have no persisted key or payload and
are deliberately ineligible for automatic recovery.

`InactiveAccountNotificationServiceReplicaIT` also simulates a process stop
after MailService accepts the request but before the audit update. With
idempotent recovery explicitly enabled, the stale undispatched row is retried
with exactly the same key and payload and then completed. Recovery is disabled
by default (`inactive.account.notification.idempotent-recovery.enabled=false`)
and must not be enabled until the deployed MailService proves that repeated
requests with the same key are accepted while producing one physical email.
The UserService test uses a conforming fake provider; it is not evidence about
the deployed provider. Until that provider conformance and runtime replay proof
exist, this remains a staged caller-side contract and the global replica limit
remains one.

The hourly enquiry-notification scheduler now acquires a global, durable
`scheduled_task_claim` before reading sessions, agencies or consultants. Its
30-minute claim is shorter than the configured hourly schedule and must remain
longer than the measured `userservice.scheduler.duration` for this task.
`EnquiryNotificationServiceReplicaIT` releases two independent instances
concurrently against the same database and observes one mail batch and one
claim. The losing instance performs no downstream database or service reads.
Expired claims are renewable; a concurrent first insert loses safely on the
database primary-key conflict. The contract also passed on MariaDB 11.0.6,
where the race surfaced as an InnoDB deadlock rather than H2's unique-key
violation; the losing transaction read back the active winning claim. Migration
0077 now seeds the known scheduler claim rows in an expired state, so normal
production acquisition serializes on an existing database row and avoids that
first-insert race.

The hourly anonymous-user deactivation scheduler uses the same durable claim
before it establishes technical tenant context or enters the lifecycle
workflow. `DeactivateAnonymousUserSchedulerReplicaIT` releases two independent
scheduler instances concurrently and observes exactly one tenant-context setup
and one deactivation workflow invocation. The losing replica performs no
session query, database transition or external provider action. The 30-minute
claim remains shorter than the configured hourly schedule and must stay above
the measured task duration.

The hourly anonymous-user deletion scheduler acquires its own durable claim
before technical tenant context and before the account/provider deletion
workflow. `DeleteUserAnonymousSchedulerReplicaIT` observes exactly one context
setup and one deletion workflow under two concurrent scheduler instances. The
losing replica performs no database deletion, provider cleanup or error-mail
work. All four existing domain integration cases remain green with isolated
claim state. Its 30-minute bound has the same hourly duration constraint.

The daily account-deletion scheduler acquires a separate durable claim before
technical tenant context and before any database or external-provider cleanup.
`DeleteUserAccountSchedulerReplicaIT` releases two scheduler instances
concurrently and observes exactly one context setup and one deletion workflow;
the losing replica performs no downstream work. The 12-hour claim remains
shorter than the configured daily schedule and must stay above the measured
`userservice.scheduler.duration` for this task.

The daily registered-only-user deletion scheduler now returns without tenant
context or database access when both deletion modes are disabled. When either
mode is enabled, it acquires a separate durable claim before tenant context,
database reads, provider cleanup or error-mail work.
`DeleteUsersRegisteredOnlySchedulerReplicaIT` enables both modes and observes
one context setup and one invocation of each mode under two concurrent
scheduler instances. Its 12-hour claim has the same daily duration constraint.

## Current dependency sequence

1. Land the Redis-backed single-use token PR #740 in `pre-dev` before
   publishing this stability stack. The stack already integrates its functional
   commit locally and removes both obsolete authentication-token entries from
   the local-state inventory.
2. Continue the provider-neutral identity boundary after the completed
   create-user slice. Application workflows now consume only the created
   identity ID; the Keycloak adapter owns missing-`Location` recovery and
   refuses ambiguous or absent authoritative matches. Password and technical
   user login also return the provider-neutral `IdentityLogin` value; Keycloak
   token JSON remains inside the adapter. The remaining lookup and magic-link
   transport seams are tracked separately.
3. Complete the Matrix-only removal workstream, which deletes all three
   Rocket.Chat inventory entries rather than retaining disabled fallbacks.
4. Prove MailService idempotency and runtime replay before enabling
   inactive-account notification recovery.
5. Run the complete two-instance integration/E2E suite without sticky sessions,
   then raise the Helm replica constraint only when
   `userservice.replica.max_supported` can truthfully change.
