# Booking appointment mail event contract (v1)

This is the input contract for a future adapter from the existing booking appointment boundary to
transactional appointment notifications. It does not activate a booking producer or a mail sender.
The self-help group implementation in this PR continues to use its Series-specific outbox.

## Source and ownership

`AppointmentController` currently creates and updates an `Appointment` through `Organizer` and
deletes it by UUID. The persisted `Appointment` has a UUID, optional external `bookingId`, a
consultant, an `Instant datetime`, and `CREATED` / `STARTED` / `PAUSED` status. It does **not**
contain an advice seeker, tenant, time zone, monotonic revision, or cancellation status. The
future producer must obtain those facts from authoritative booking/session state. It must not
infer them from a browser request, email address, or a missing-field fallback.

## Normalized event handed to the adapter

| Field | Contract |
| --- | --- |
| `schemaVersion` | `1`; reject unknown versions rather than guessing a shape. |
| `source` | `BOOKING`; keep booking IDs separate from self-help Series IDs. |
| `appointmentId` | Stable persisted appointment UUID. `bookingId`, when present, is only an external reference and is not the delivery key. |
| `sourceRevision` | Monotonic positive revision for this appointment, including changes and cancellation. A replay of the same revision has the same meaning. The current entity does not provide this yet. |
| `kind` | `CONFIRMED`, `RESCHEDULED`, or `CANCELLED`. The 24-hour `REMINDER` is derived from a current confirmed occurrence, not accepted as an unauthenticated producer command. |
| `scheduledStartUtc` | Exact UTC instant for the new date; for cancellation, the last known scheduled instant so the recipient knows which date was cancelled. |
| `timezone` | IANA zone used for recipient-facing local date and time. A numeric offset alone is insufficient across daylight-saving changes. |
| `ownerTenantId` | Tenant whose booking owns sender, brand, SMTP route, and configured public app origin. A cross-tenant consultant does not silently change the sender. This ownership rule must be confirmed before activation. |
| `recipientRefs` | Authoritatively resolved advice seeker and consultant IDs with explicit roles. Never accept recipient addresses or SMTP settings from the browser/event payload. Recheck identity, participation, email address, preference and tenant policy at delivery time. |
| `recordedAtUtc` | Source event time for audit and ordering; not a substitute for `sourceRevision`. |

The adapter rejects an incomplete event before creating delivery work. It must not use the
existing `group_appointment_mail_outbox` directly: that table's unique key is based on
`series_id` and `occurrence_index`, neither of which identifies a booking appointment.

## Delivery invariants

1. Deduplicate per `BOOKING`, `appointmentId`, `sourceRevision`, `kind`, recipient role and ID.
   A newer revision suppresses an unsent confirmation or reminder for an older date. Replaying
   a committed event never creates another recipient claim.
2. Persist the event and durable per-recipient claims before transport. Commit a claim before
   SMTP/OWN handoff; ambiguous outcomes require correlation-based operator reconciliation, not
   blind retry. Bound retries and work per poll as in the self-help mail worker.
3. Use the recipient's supported language and appointment-mail preference. A missing address or
   missing required sender/public URL suppresses or fails visibly; it never falls back to a
   guessed host or tenant. Preserve the distinction between email and in-app preference.
4. Keep subject, preview, log and correlation data free of counselling topic, case text, names,
   recipient address and sensitive category. The action link must require authentication and
   open the booking in the recipient's authorized role; it must never act as a group invite.

## Producer work deferred by product direction

Before connecting `AppointmentController` or an external AppointmentService event source, define
where the missing advice-seeker/tenant/timezone mapping and monotonic revision are maintained,
and how deletion emits a cancellation snapshot before the appointment disappears. Add the
booking-specific durable outbox and authenticated return route, then prove both roles, all seven
mail variants, reschedule/cancel/replay, DST, preference isolation, and real Dev receipts. None
of those producer or delivery steps is claimed by the self-help group PR.
