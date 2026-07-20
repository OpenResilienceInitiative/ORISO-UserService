# Conversation Membership & Visibility

How counsellors are present in a client conversation (Matrix room): who is in the
room, who may see it, and how a counsellor who is *not* the active one discovers
and takes over a case (Case Handover / Fallübergabe, roadmap `CAR-CHO-01`).
Governing invariant and full vocabulary (Silent member, Reveal, Department, Owner
vs active, ...) are decided in `ADR-002-silent-room-membership-and-access-control-curtain.md`
(workspace root). This file tracks the **Admin-configurable policy shape** as it is
grilled and refined (2026-07-04 session), extending — not replacing — ADR-002.

## Language

**Handover reason**
One predefined, fixed reason a counsellor selects to explain why access is needed
(e.g. holiday, illness, advice needed, legal violation). Drives both the **access
outcome** and the client-facing system message. Reasons are platform-defined, not
tenant-authored freetext.

**Colleague Consultation** (decided 2026-07-13, grill-with-docs — renamed from
"advice needed" / `COUNSELLOR_ASKED_FOR_ADVICE`):
The co-access reason for "I asked a colleague to look at my case with me."
Deliberately renamed to emphasize this is **not** a transfer of ownership and
reads closer to peer coaching than the takeover-class reasons (holiday, illness,
left, legal violation) this feature otherwise handles. Stays a Case Handover
reason (not re-homed to Supervision) — it still needs the reason/policy/audit
machinery Case Handover already provides. Its underlying mechanism is the same
co-access outcome (read-only, time-boxed, reuses the Supervision primitive) as
before; only the label changed. See `CONTEXT-conversation-types.md` for the
distinct, standing **Supervision (auto-assigned)** concept — related but not the
same feature.

**Access outcome**
Whether a reason is **co-access** (read-only, time-boxed, original keeps full
visibility) or **takeover** (full ownership, original re-hidden but keeps
membership). Decided in ADR-002. **Represented via `Maximum Session Duration`**: a
reason with a finite duration is co-access; a reason with no duration ("until
reclaim") is takeover. This is one field, not two — do not add a separate
co-access/takeover enum unless a reason needs a *bounded* takeover (none identified
yet).
_Avoid_: encoding access outcome only via the consent toggles — consent (who must
agree) and access outcome (what results) are independent axes.

**Approval role (consent axis)**
Per reason, which parties must consent before activation: *advice seeker
(client)*, *advisor (original counsellor)*, or neither. Independent of access
outcome. For takeover-class reasons where the original counsellor is absent
(illness, holiday), their consent is structurally excluded — they cannot be asked.
**Extensible list, not two fixed booleans (Frank 2026-07-06):** a reason may
require 2–3 approvers, configurable per reason — model the schema as a set of
approver roles per reason so new roles can be added without reshaping the UI
concept. The current Admin card renders two rows (client/advisor) as the first
two entries of that list.
_Avoid_: a single flat "who must agree" field that also tries to imply access
outcome; hardcoding exactly-two consent columns.

**Reclaim eligibility**
Whether the original counsellor can retake an active case from the cover, exposed
as an icon in the chat-history menu. **Derived, not independently configured**:
co-access reasons never need reclaim (original never lost visibility); among
takeover reasons, only temporary-absence reasons (illness, holiday) are
reclaimable — permanent reasons (left/dismissed, legal violation) are not.
_Avoid_: a separate admin toggle for reclaim-eligibility per reason; it should
follow from the reason's own semantics (permanent vs temporary takeover).

**Client consent floor (platform-enforced)**
A platform-level minimum, locked via the enforce mechanism so no tenant/agency can
turn it off: the client must consent before any takeover. Default mode is
**implicit/passive** — a system-message notification informs the client, and their
prior platform privacy-policy acceptance stands as consent unless they have
opted out. A client may **opt out** of passive consent at any time (client-facing
preference, storage location TBD); once opted out, future handovers on their case
require **active** consent, and if they decline or don't respond the handover is
blocked (case stays with the unavailable counsellor rather than being forced
through).
_Avoid_: conflating this floor with the per-reason approval-role setting — the
floor is a platform-wide non-overridable minimum; approval-role is the
tenant/reason-level configuration on top of it.

**Reason disclosure**
A separate, per-tenant-configurable setting: whether the client-facing system
message names the specific handover reason (e.g. "ill") or shows a generic
message. Distinct from both the consent floor and the **Custom Explanation**
freetext field (the counsellor's optional note). Real conflicting tenant needs
observed: some tenants withhold the specific reason from clients (safeguarding /
preference), others want it disclosed (so the client understands team dynamics).

**Handover audit entry (revised 2026-07-04 — supersedes earlier UAT)**
Structured-only log entry: actor (who), exact timestamp (when), and handover
reason. No free-text explanation field — **Custom Explanation is dropped
entirely**, along with any external "Law Enforcement Representative" approval
role (out of scope for this feature; external legal disclosure, if ever needed,
is a separate process, not a role in this dropdown). This **overrides** the
earlier UAT doc (`Case Handover Fallübergabe KDG-compliant.md`, Scenario E),
which required a mandatory free-text explanation before a request could be
sent — that scenario is no longer accurate and should be revised when the UAT
doc is next touched. Visible to Träger (tenant-admin) and Beratungsstelle
(agency-admin), via the existing Admin Panel Logs feature. **Retention: 364
days, hard cap** (delete after, not a floor) — adjustable later if a longer
legal requirement is identified. This resolves the previously-open "audit
retention" question from the original UAT doc.

**Client opt-out mechanics (decided 2026-07-06)**
The opt-out lives **inside the conversation as an interactive switch in a system
message**: when the client enters a (team-counselling) session, a short system
message explains that this is a team agency — others do not actively see the
conversation, but on demand access is possible (sometimes briefly, sometimes as
a handover for reasons like illness). The message carries an "allow" switch,
**default ON**. Switching it OFF means: from that moment, team access is not
allowed; the client can scroll back and toggle it again later (both directions,
each effective from the moment of switching). A client's "no" **overrides every
per-reason setting** — handover/co-access is blocked or requires active consent.
This resolves the previously-open floor-vs-per-reason question: card-level
opt-out is the platform floor (default on), per-reason consent settings can only
be stricter, and the client's own switch trumps both.
_Test requirement_: this retroactive on/off semantics needs an explicit test
spec before implementation (scroll back → disable → access blocked from that
moment; re-enable → allowed again) — do not build it blind.

**Legal-violation reason (decided 2026-07-06)**
"Rechtsverletzung" is a normal reason code (takeover-class, no reclaim), shown
in the Admin card as a **disabled placeholder tab** until the backend seeds it.
Purpose of showing it: demonstrate that case handover can work above the agency
level — later a responsible authority (e.g. the Träger's legal counsel) can be
attached to this reason. Not an external-disclosure mechanism (that stays out,
see the dropped Law-Enforcement role above).

## Open

- Does a finite `Maximum Session Duration` always imply read-only access, or could
  a reason need a time-boxed *full* takeover? No case identified yet — treat
  duration-present as sufficient signal for co-access until one appears.
- Where exactly is the client's opt-out switch state stored (per-conversation
  flag on the session vs profile preference) and can a Träger disable the
  opt-out itself? The UI decision (in-chat system-message switch, default on,
  retroactive) is made — the storage location is still open.
