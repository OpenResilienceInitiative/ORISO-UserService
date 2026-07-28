# Matrix group-chat deactivation replica safety

The one-minute group-chat deactivation scheduler does not use a coarse global
claim. `ChatRepository.findAllByActiveIsTrue()` takes a pessimistic database row
lock, so concurrent scheduler instances serialize each active chat while its
database transition and Matrix room purge are in progress.

`DeactivateGroupChatSchedulerMariaDbReplicaIT` opens two transactions and then
starts two scheduler instances against one expired Matrix chat on MariaDB. Both
instances enter the workflow, but the second remains blocked while the first
purge is held open. The test scopes all setup and cleanup data to its own Matrix
room. The chat is deleted once and its Matrix room is purged exactly once.

This is a concurrent-replica serialization guarantee, not crash-safe exactly
once delivery. A process stop after Matrix accepts the purge but before the
database transaction commits can replay the purge. Removing that crash window
requires an idempotent Matrix operation plus a durable state transition or
outbox; the database row lock alone cannot provide it.

The reusable MariaDB workflow requires this proof. The coordination is scoped to
the Matrix-only deactivation path; no Rocket.Chat or Jitsi fallback is involved.
