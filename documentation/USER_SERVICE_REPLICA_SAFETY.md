# UserService replica-safety contract

UserService is currently safe to deploy with one replica. A Kubernetes
`Deployment` and stateless HTTP security do not make the whole process
stateless: Matrix listener coordination, authentication tokens, active-view
notification state, Ehcache reads and scheduled side effects still have
process-local behavior.

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

SigNoz already receives `service.instance.id` as a resource attribute. Grouping
`userservice.scheduler.executions` by task and service instance makes duplicate
multi-replica execution visible without adding pod names as metric tags.

These signals prove inventory presence and execution behavior; they do not
prove a scheduler is leader-safe. The deployment must remain at one replica
until the catalog decision for every correctness or duplicate-side-effect item
is resolved and a two-instance integration suite passes without sticky
sessions.

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
