# AVV group admission and recurring continuation

Issue: https://github.com/OpenResilienceInitiative/ORISO-TenantService/issues/294
Group delivery: https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/1323

Frank confirmed on 28 October 2026 that actual existing participants may continue at later
appointments of their group. A newly registered, invited or assigned actor is not an existing
participant. Current JOIN and trusted previously applied JOIN in the same persisted Series
are participation evidence. Normal tenant, role, invitation and ban rules still apply.

The owner AVV decision applies to new SELF_HELP participation at every occurrence,
including legacy repetitive rows. Internal colleague groups remain exempt. Opening a later
occurrence remains possible for the existing participants; it does not grant newcomers entry.

## Creation is assignment, not participation

SELF_HELP creation stores the selected co-moderator assignments without auto-JOINing them.
Each selected actor passes a fresh owner decision after room creation. Its Matrix account
mapping is an administrative prerequisite established before assignment commit; failure returns
502 and follows the existing owned creation compensation, without deactivating another person's
account. Account setup, registration and assignment do not establish counselling participation.

Ordinary invitations are sent only after the canonical Chat/room and assignments commit, so an
independent native callback sees the counselling classification. Each invitation dispatch uses
a new transaction to recheck current room ownership, active same-tenant co-moderator assignment
and fresh AVV policy. An invitation failure remains pending; the committed metadata is retained.
The existing authorized public JOIN path can retry the invitation and commences participation
only when the current policy permits it. A creation 201 means metadata was created, never that
selected people joined. Internal colleague/members-only creation keeps its existing auto-JOIN.

## Paired private contract

The original dedicated-credential v1 request and exact per-room applied-history contract remain
supported. The Synapse companion uses an additive operation on the existing private callback:

```text
POST /internal/matrix/group-join-policy
X-Oriso-Group-Policy-Token: dedicated paired credential
Content-Type: application/json

Request (exactly four properties):
{"contractVersion":2,"operation":"CONTEXT","roomId":"!room:server","matrixUserId":"@actor:server"}

Response (exactly six properties):
{"contractVersion":2,"roomId":"!room:server","matrixUserId":"@actor:server",
 "applicable":true,"roomIds":["!room:server","!earlier:server"],"historyComplete":true}
```

CONTEXT performs only persisted provenance reads. It neither checks membership nor calls the
AVV owner or history HTTP endpoint; it takes no group lock. Its room IDs are server-owned,
unique, bound to the same Series and current owner tenant, and ordered with the requested
room first. Only that room and its earlier occurrences are included. Unknown ordinary rooms
and internal groups return `applicable=false`, an empty room list and `historyComplete=true`.
Ambiguous or corrupt provenance fails with sanitized 502. Maximum 512 rooms and 65,536
response bytes; exceeding a bound fails rather than truncating participation evidence.

The native companion checks real accepted/applied membership across these rooms. If an actual
JOIN exists, only the AVV new-work check is skipped. Otherwise it invokes the original v1 owner
veto. Only 403 with `X-Reason: DPA_NEW_COUNSELLING_NOT_ALLOWED` and the canonical JSON body
`{"reason":"DPA_NEW_COUNSELLING_NOT_ALLOWED"}` is a confirmed legal denial;
other failures are verification failures. No miss is cached. Ordinary Synapse authorization
must still run. The history resource is private and performs no callback, avoiding recursion.

Before normal recurring-room purge, the same private history resource accepts exactly
`{"contractVersion":2,"operation":"SNAPSHOT_ROOM","roomId":"!room:server"}`. Its exact
acknowledgement is `{"contractVersion":2,"roomId":"!room:server","complete":true}`. The
companion must durably snapshot all trusted applied JOINs, including former participants who
already left. A missing, malformed or failing acknowledgement prevents purge. Message retention
and ordinary purge behavior remain unchanged; the receipt contains no message body.

True group deletion first confirms purge of retained earlier/prepared rooms, then uses
`FORGET_ROOM` with the same v2 request/acknowledgement shape for every recorded current,
earlier and prepared room before deleting provenance. The current room retains its existing
caller-owned purge semantics. A cleanup failure retains the Series for retry; earlier successful
remote erasure is irreversible and the retry must finish the same requested deletion. This is separate from recurrence, which retains the receipts. Native
Synapse deactivation must erase actor receipts and prevent later bootstrap resurrection; that
companion behavior needs its own actual-version proof.

## Persisted room provenance and legacy data

`chat_matrix_room` binds a globally unique room to a Series, occurrence and owner tenant.
The migration copies only unique current canonical Chat mappings; it never guesses older
rooms from names, aliases, assignments or invitations. Rotation durably reserves the next room before copying members, then atomically advances the canonical Chat pointer. Retries reuse the exact owned reservation; stale or ambiguous provenance fails safely.
Deleting the Series cascades its provenance; rollback drops only the added provenance table.

Earlier software overwrote each Series' old room ID. For an already advanced legacy Series,
its older provenance can therefore be incomplete. This is explicit in `historyComplete`:

- An applied JOIN in any proved room still establishes continuation.
- No proved JOIN plus a fresh allowing owner decision permits new work.
- No proved JOIN plus a denying owner decision and incomplete lineage returns sanitized 502;
  the system cannot truthfully classify an uncertain former participant as a newcomer.
- Actual history transport/schema failures remain 502, including when the owner would allow.

Full legacy acceptance requires independently recovered authoritative Series-to-room records
or a retained proved applied JOIN in a known room. Enabling the companion does not recreate
lost provenance. This unresolved data gate must not be described as completed acceptance.

## Verification and rollout boundary

Authenticated UserService HTTP regressions exercise later-entry refusal, earlier-room
continuation, partial-history behavior, owner tenant binding, malformed history and native
CONTEXT/v1 behavior. The migration is executed against SQL in tests. These checks do not
prove loaded Synapse enforcement or a deployed browser journey.

Roll out only with the pinned Synapse companion, dedicated secret, private history listener,
internal callback isolation and matching enabled UserService configuration. Keep live rollout,
actual native JOIN/Postgres history, direct roles and end-to-end Dev acceptance separate from
source readiness. Never expose the private history resource on the public Matrix listener.
