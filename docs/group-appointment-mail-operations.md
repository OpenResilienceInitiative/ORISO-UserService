# Self-help appointment mail operations

The group appointment producer writes one outbox row for each recipient, occurrence revision and
occasion. The worker is disabled by default with `group.appointment.mail.enabled=false`. Enable it
only after the Frontend `login?seriesId=<id>` return path, UserService sender, TenantService four
`SELF_HELP_APPOINTMENT_*` relay purposes and public tenant origins are deployed together. The
worker polls every minute by default (`group.appointment.mail.poll-delay-ms=60000`). The general
booking event producer is separate and deferred; its required input and delivery invariants are
recorded in [the booking adapter contract](appointment-mail-event-contract.md).

## Delivery states

| State | Meaning | Operator action |
| --- | --- | --- |
| `PENDING` | Due later, or not yet claimed. A configuration error leaves it here with `next_attempt_at_utc` moved forward (five minutes up to one hour). | Fix the named configuration error; do not change its tenant or public URL silently. Other tenants remain selectable on the next poll. |
| `SENDING` | Claim committed before SMTP/OWN handoff; the process may have stopped. | Reconcile its correlation ID before any manual state change. |
| `SENT` | Transport accepted the handoff; final mailbox receipt is a separate check. | No replay. |
| `SUPPRESSED` | Recipient, preference, occurrence or revision was no longer eligible. | No replay. |
| `UNCERTAIN` | SMTP/relay outcome could not be proved. | Reconcile; never reset to `PENDING` solely because no receipt was reported. |

To locate work without exposing recipient addresses, run a bounded read-only query:

```text
SELECT id, series_id, occurrence_index, occurrence_revision, event_type,
       recipient_role, status, due_at_utc, next_attempt_at_utc, failure_count,
       claimed_at, sent_at, correlation_id
FROM group_appointment_mail_outbox
WHERE status IN ('PENDING', 'SENDING', 'UNCERTAIN')
ORDER BY due_at_utc, id
LIMIT 100;
```

Each row has a stable opaque `correlation_id`. In PLATFORM mode it is the
`X-ORISO-Correlation-ID` MIME header. In OWN mode TenantService receives the same ID and adds the
header. Check SMTP provider acceptance and the receiving mailbox against that ID. A timeout can
occur after SMTP accepted the message, so absence of a success log does not prove non-delivery.
Record the result and reviewer before any manual replay or closure. Do not log or paste recipient
addresses, group topics, names or message content into the incident record.

## Activation check

1. Confirm the sender tenant is the group owner's tenant and has a configured public origin and
   explicit PLATFORM or complete OWN SMTP route. A cross-tenant counselor uses the group origin;
   this policy is provisional until product approval.
2. Confirm `login?seriesId=<numeric-chat-id>` opens the current group for an eligible participant
   and counselor after sign-in, and rejects an unrelated group for each role. It must never call
   the `gcid` invitation ASSIGN action.
3. Confirm the four role/event templates exist in all seven tone directories:
   `de-sie`, `de-du`, `en`, `fr`, `ru`, `ti`, `tr`. Human language review of the machine variants
   remains a separate release decision.
4. Enable the worker for a controlled Dev run and verify a received confirmation, reschedule,
   cancellation and 24-hour reminder for both roles, including an OWN and a PLATFORM route.
   Compare the received correlation header to the outbox row and check neutral subject/preview,
   local date and time, links, and absence of group topic or participant identity in the mail.
5. Keep the worker disabled in Stage until the Stage operator supplies its origins and SMTP
   settings and separately verifies deployment revision, receipt and login return path.
