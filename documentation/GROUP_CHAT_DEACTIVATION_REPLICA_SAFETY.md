# Matrix group-chat deactivation replica safety

The one-minute group-chat deactivation scheduler does not use a coarse global
claim. `ChatRepository.findAllByActiveIsTrue()` takes a pessimistic database row
lock, so concurrent scheduler instances serialize each active chat while its
database transition and Matrix room purge are in progress.

`DeactivateGroupChatSchedulerMariaDbReplicaIT` starts two scheduler instances
against one expired Matrix chat on MariaDB. Both instances enter the workflow,
but the second remains blocked while the first purge is held open. The chat is
deleted once and its Matrix room is purged exactly once.

The reusable MariaDB workflow requires this proof. The coordination is scoped to
the Matrix-only deactivation path; no Rocket.Chat or Jitsi fallback is involved.
