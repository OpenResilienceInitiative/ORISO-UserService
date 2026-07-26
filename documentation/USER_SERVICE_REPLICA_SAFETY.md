# UserService replica-safety contract

UserService is currently safe to deploy with one replica. A Kubernetes
`Deployment` and stateless HTTP security do not make the whole process
stateless: Matrix listener coordination, authentication tokens, Ehcache reads
and scheduled side effects still have process-local behavior. Notification
active-view state is now externalized as described below, but the remaining
items still keep the global replica limit at one.

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

The inactive-account notification proof starts two independent service
instances against the same audit database. A transaction-isolated unique claim
is committed before the external mail call, so the losing instance performs no
mail call and no longer needs a separate existence query. MailService reports
whether it accepted the request; only accepted requests set
`emailDispatched=true`, while rejected requests remain auditable as
undispatched.

This is an at-most-once concurrency guarantee, not a crash-recovery guarantee.
A process can still stop after MailService accepts the request but before the
audit update. Automatic replay therefore requires a provider idempotency key or
an outbox/lease protocol that can reconcile that ambiguous state. The global
replica limit remains one.

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

1. Merge the Redis-backed single-use token work from issue 739 and remove the
   two authentication-token entries from the local-state inventory.
2. Complete the Matrix-only removal workstream, which deletes all three
   Rocket.Chat inventory entries rather than retaining disabled fallbacks.
3. Externalize or lease the Matrix sync/listener state and prove notification
   idempotency under two instances.
4. Define cache invalidation bounds for tenant and application-setting caches.
5. Add concurrent scheduler contracts, then raise the Helm replica constraint
   only when `userservice.replica.max_supported` can truthfully change.
