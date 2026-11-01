# Activity Timeline: which rooms create entries, and when the active view suppresses them

Written for ORISO-Frontend#1824 (slice S6), where the Timeline preview becomes writable.
The preview never sends `PATCH /users/event-notifications/active-view`. So a person whose
only "view" of a room is the preview keeps getting Timeline entries for that room. The
tests listed at the end prove this. No production code was changed.

## Suppression rule

`EventNotificationService#shouldSuppressNotification` drops an entry only when **the
recipient's own** active view matches:

| Entry | Suppressed when the recipient's active view is ... | Not suppressed by |
| --- | --- | --- |
| `message.new` | on the same room (main timeline or any thread of it) | another room, no view, a view older than 30 s |
| `thread.reply.new` | on the same room **and** the same thread root | the main timeline of that room, another thread, another room |
| `team.discussion.new` | on the team-discussion room itself (main timeline or any thread; the check is room-level) | the enquiry's own room, any other room |

The active view is one entry per user, kept in memory (TTL 30 s, local to each replica; see
`docs/replica-safety.md`). It affects the Timeline only. Push and email do not check it.

## Supervision side room (ADR-008)

| Message in the side room | Timeline entry | Who |
| --- | --- | --- |
| Supervisor writes (`supervisorMessage=true`) | none | — |
| Counsellor writes | none | — |
| Thread reply by either | none | — |

Why: the side-room id is stored on `session_supervisor.matrix_room_id`
(`SessionSupervisorFacade#ensureSupervisionSideRoom`), never on `session.matrix_room_id`.
Both message producers (`POST /message-events` and the Matrix listener) resolve the room with
`SessionRepository#findByMatrixRoomId`, find no session and stop. An active view cannot
suppress anything here, because nothing is created. In the other direction, an active view on
the side room does not suppress `message.new` for the main room.

A supervisor message that goes to the **main** room (legacy rows without a side room, still
flagged `supervisorMessage=true`) creates `message.new` for the counsellor only, never for the
advice seeker. Only the counsellor's own view of that main room suppresses it.

Side-room messages can still trigger the feedback email (`FeedbackMessageEmailService`, when
the client sends `feedbackMailIntent`). That email is not a Timeline entry and does not check
the active view.

## Team discussion (internal counsellor chat about an enquiry, US#473)

| Post | Timeline entry | Recipients |
| --- | --- | --- |
| First post of an `OPEN` discussion | `team.discussion.new` | every consultant of the enquiry's agency except the sender |
| Later posts | `team.discussion.new` | participants (colleagues who opened or wrote in the discussion) plus mentioned colleagues, except the sender |
| Post with `mentionedUserIds` | `team.discussion.new` with `params.mentioned=true` | the mentioned colleague (must belong to the agency), even if they never took part |
| `ARCHIVED` discussion, or the enquiry is already accepted | none | — |

The colleague's per-conversation level (Muted / Snoozed / Mentions only) is applied first.
After that, only an active view on the team-discussion room itself suppresses the entry.

## Tests

- `EventNotificationServiceTest`, tests prefixed `s6_` (unit tests: real
  `EventNotificationService`, and for the team discussion the real
  `TeamDiscussionNotificationService`; repositories are mocked).
- `EventNotificationControllerTest`, tests prefixed `s6_` (controller wired to the real
  services: `PATCH /active-view` followed by `POST /message-events`).
