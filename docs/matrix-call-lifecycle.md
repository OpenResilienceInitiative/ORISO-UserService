# Matrix call lifecycle contract

## Identity and trust

A call is identified by the pair `sourceRoomId` and `callId`. Its dedicated media
room cannot be rebound to another call or conversation. A source conversation is
either a Session or a Chat, never both, and supplies the tenant.

The source-room `org.oriso.call.invite` event carries `call_id`, `call_room_id`,
`lifetime` (milliseconds), and `is_video`. Its homeserver timestamp is the
invitation time. The media room must expose matching `org.oriso.call.binding`
state with `call_id` and `source_room_id`. Before accepting the binding, the
backend checks the source sender's current room membership and active domain
identity in the conversation's tenant.

Invite identifiers are validated before persistence: call IDs have at most 191
characters; source-room, media-room and sender IDs have at most 255. Invalid
invitations are rejected without preventing later valid events in the sync batch.

An expired invitation does not ring again. It may establish a validated binding
so an ongoing call can be observed after delayed sync. A first-seen expired
invitation does not infer past invitees from today's room members. An existing
binding retains its original invitation recipient snapshot.

## Authenticated state read

`GET /service/matrix/calls/state?sourceRoomId=…&callId=…`

The controller also accepts the unprefixed `/matrix/calls/state` route. Clients
must URL-encode both query values. The existing Matrix API security rules apply.
The caller is taken from the authenticated identity, not from query parameters.
The read additionally requires an active domain identity, matching tenant, and
current membership of the source room. Unknown or unauthorized calls return an
empty `404`. Successful responses use `Cache-Control: no-store`.

| Field | Meaning |
| --- | --- |
| `sourceRoomId`, `callId`, `callRoomId` | Immutable conversation, call, and media-room identity |
| `callType` | `audio` or `video` |
| `state` | `invited`, `running`, `ended`, or `missed` |
| `invitedAt` | Invitation timestamp, integer epoch milliseconds |
| `startedAt`, `endedAt` | Integer epoch milliseconds, or `null` when not known |
| `durationSeconds` | Whole nonnegative seconds; zero without attendance |
| `participantMatrixIds` | Sorted distinct Matrix IDs with persisted observed attendance |

`invited` means no attendance has been observed and the call is not terminal.
`running` means attendance has been observed without a terminal transition.
`ended` means a call with attendance has finished. `missed` means it finished
without observed attendance. These states describe persisted Matrix membership,
not proof that audio or video actually flowed.

The frontend checks the complete immutable identity before applying this data
to an existing encrypted `org.oriso.call.lifecycle` room card. It converts epoch
milliseconds to the card's ISO timestamps and resolves participant names from
the source Matrix room. It does not edit another sender's encrypted message.
Joining from a card requires a fresh authenticated state read with matching
conversation, call, media-room and call-type identity and a running state before
any media-room join. Cancellation or an account change while that read is pending
invalidates the attempt.

## Replay and recovery boundaries

Device membership state and historical attendance are persisted. Room processing
is locked per binding; recipient notifications use durable deduplication keys.
The listener advances its sync cursor only after batch processing succeeds.
`rooms.leave` invalidates observation of a nonterminal media room. Rejoining
revalidates the binding and an active source member, and does not enable expiry
until fresh media-room state has been received.

A successful state read is not a complete offline event-history reconstruction.
Neither a local test, a stored membership, nor a built artifact proves deployment
or working media. Delivery acceptance separately requires authenticated,
two-participant browser tests against the intended deployment.
