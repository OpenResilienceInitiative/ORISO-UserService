# Live-chat observability contract

These signals answer bounded operational questions without putting tenant,
topic, session, room, user, event, URL, token, email, exception text, or message
content into metric dimensions.

| Metric | Type | Allowed tags | Diagnostic use |
| --- | --- | --- | --- |
| `oriso.live_chat.routing.decisions` | counter | `stage={availability, eligibility}`; `outcome={available, invalid_topic, no_assignment, no_eligible_consultant, availability_expired, presence_unavailable}` | Separates missing assignment, absent/ineligible consultants, expired activity leases, and unavailable Matrix presence. |
| `oriso.live_chat.routing.candidates` | distribution summary | same bounded routing tags | Shows the numeric candidate population without consultant identifiers. |
| `oriso.live_chat.queue.visibility` | counter | `demand={none, present, unknown}`; `outcome={observed, invalid_request}` | Distinguishes observed empty demand, non-empty demand, and invalid requests that cannot answer the demand question. |
| `oriso.live_chat.queue.depth` | distribution summary | none | Measures pending enquiries ahead of a request. |
| `oriso.matrix.room.creation` | counter | `encryption={enabled, disabled}`; `outcome={success, failure, skipped}` | Proves encryption posture at the completed Matrix creation request, not from configuration alone. |
| `oriso.matrix.event.processing` | counter | `event_type={message, encrypted_message, call_invite, call_answer, call_hangup, other}`; `outcome={success, failure, skipped}` | Separates handled, rejected, and failed Matrix events without room/event IDs. |
| `oriso.matrix.side_effect.operations` | counter | `side_effect={mobile_push, notification}`; `outcome={success, failure, skipped}` | Shows asynchronous notification outcomes after each side effect completes. |

All tag values originate from enums or a fixed Matrix event-type allowlist. An
unknown Matrix event type becomes `other`; its raw value is never registered as
a tag. Metric recording is best-effort and cannot change a business outcome if
the meter registry fails.

## Symptom routing

- Empty queue and `demand=none`: no demand, not a routing outage.
- `no_assignment`: the topic has no consultant relation.
- `no_eligible_consultant`: assigned consultants are absent or Matrix reports
  nobody online.
- `availability_expired`: eligible consultants exist, but no live Redis
  availability lease remains.
- `presence_unavailable`: Matrix presence could not be used and the deliberate
  assignment fallback was taken.
- Room-creation `outcome=failure`: Matrix provisioning failed.
- Successful room creation with `encryption=disabled`: critical security alert.
- Event or side-effect `outcome=failure`: Matrix processing or notification
  failure respectively.

The deployment must still prove these metrics in self-hosted SigNoz on PreDev
and then Dev. Unit tests prove the instrumentation contract, not live delivery.
