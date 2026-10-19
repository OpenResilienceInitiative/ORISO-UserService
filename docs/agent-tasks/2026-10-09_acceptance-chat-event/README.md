# Initial-acceptance chat event — local implementation

The original chat work is retained: Frontend issues #1642/#1657 and merged PRs #1648/#1661.
Merged UserService PR #1367 preserves standing preferences and immutable handover metadata.
The new first-acceptance producer is a separate slice tracked by UserService issue #1382.
The new event does not grant additional access and does not decide a handover request.

## Private-room contract

```text
[SYSTEM_NOTIFICATION]{"type":"INQUIRY_ACCEPTED","username":"public pseudonym",
 "acceptance":{"sessionId":231,"acceptedAt":"2026-10-09T10:20:00Z"}}
```

Plain `title`/`description` are also included for older frontends. Their reviewed existing
`notifications.events.inquiryAccepted` catalogue text is copied into `inquiry-acceptance-copy.json`
(de/en/fr/tr/ru/ti) and snapshotted from the persisted session language, matching
CaseHandoverService.resolveSessionLanguage. Missing/unsupported locale falls back to German,
matching that existing contract. The text claims acceptance only, never additional access or
encryption. Request cookies and async-thread locale are not used.

`username` is optional and comes only from ConsultantDisplayNameResolver's public
name / decoded username rule, never firstName, lastName or internalDisplayName.
`acceptedAt` is captured in the winning database ownership transition and retained
through provisioning and sending. Matrix event time records actual delivery;
there is no historical reconstruction or backfill. No bot or new key holder is added.

## Transaction and delivery states

1. Existing short SessionOwnershipService transaction locks the session, validates
   expected owner/revision/row version, writes the initial INITIAL/NEW→IN_PROGRESS
   owner and atomically stores a session-unique PREPARING fact. A rollback removes both.
   Preassigned NEW enquiries count too. No new broad facade transaction is introduced.
2. Registered facade activates the fact only after its Matrix provisioning returns
   successfully. Matching compensation deletes only the same revision's PREPARING fact.
   Anonymous Live Chat and other modalities do not get this event.
3. If an existing controller transaction surrounds acceptance, delivery is deferred to
   afterCommit; rollback sends nothing. Otherwise activation commits before delivery.
4. PREPARING is never activated by the scheduler. Age and room membership cannot prove
   successful provisioning: department counsellors may already be silent members, and the
   original operation can still fail after any grace interval. An interrupted preparation
   stays durable and visibly unresolved for operator reconciliation. Only the facade's
   recorded successful return activates it; compensation cancels its matching preparation.
5. Token mint failure stays PENDING with a separately committed one-minute retry delay.
   The bounded worker reads due rows ordered by next attempt time and session id. Deferring
   blocked rows lets later deliverable rows move forward, including the 101st row behind a
   blocked batch of 100. A separately committed REQUIRES_NEW claim sets UNCERTAIN before
   sending. Only a returned event_id permits separately committed SENT acknowledgement.
   UNCERTAIN never resends automatically.

Synapse admin impersonation mints a fresh short-lived token. A deterministic transaction
id is included, but token-scoped deduplication cannot prove exactly-once after an ambiguous
handoff with a fresh token. Timeout, process crash after claim, or failed acknowledgement
therefore remains UNCERTAIN. An operator must reconcile that room's acceptance payload and
recorded event before authorizing any replay; this patch introduces no replay endpoint and
makes no completed-delivery claim for those rows.

## Schema and operation

Additive Liquibase changeset `20261009_inquiry_acceptance_notice/changeSet.xml`:
`inquiry_acceptance_notice`, one row per session, FK cascade on session deletion,
indexed delivery state / next attempt time / session identity. Rollback drops only this new table; roll back
application code before dropping it. Existing session history and handover tables are
untouched. There is no historical seed/backfill.

```text
inquiry.acceptance.notice.retry-delay-ms=60000
```

For developers — inspect unresolved delivery without secrets or participant content:

```sql
SELECT session_id, accepted_at_utc, ownership_revision, delivery_state, next_attempt_at_utc, matrix_event_id
FROM inquiry_acceptance_notice
WHERE delivery_state IN ('PREPARING', 'UNCERTAIN')
ORDER BY accepted_at_utc;
```

## Verification boundaries

The regression first failed because the real facade never called the chat-event producer
(see `red.log`). Current focused unit and real-transaction test receipts are written here.
Final reviewed Java 21 receipt: 163 tests passed across eight focused classes, including real JPA atomic
rollback/compensation/concurrent winning assignment and independently committed claim
tests, plus real Liquibase migration/uniqueness/cascade/rollback proof.
The preparation race first reproduced a false SENT event while the operation remained in flight.
The fair-queue regression first left the valid 101st row PENDING behind 100 blocked tokens.
Both are green after the reviewed corrections. The full repository suite, package and final
format gate remain root-agent gates.
No browser, account, merge, deployment, or GitHub mutation was performed by this worker.
Frontend rendering, Dev deployment and recipient browser readback remain required.

## Root final verification — 9 October 2026

Independent final transaction/recovery/queue review: PASS after the two meaningful RED/GREEN fixes. Separate envelope/public-name/localization/compatibility review: PASS. Full Java21 repository test:6,365 passed; package passed with the actual pom properties `-Dskip.unit-tests=true -Dskip.integration-tests=true`; Spotless check passed.

The documented older `package -DskipTests` command repeats the configured unit-test execution because the pom uses `skip.unit-tests`. That repeat ran6,365 tests and hit one unchanged `AccountInactivityRoleChangeTest` assertion, although both ordinary full-test runs passed. No lifecycle assertion or production guard was weakened. This local observation does not establish a baseline cause and must remain visible in the PR.

Target is dev; only local Java/source/integration evidence exists. No live acceptance notice, deployment or completed full Dev handover is claimed. Source receipts are `test-receipt.json` and `full-gate-final-receipt.json`; raw local logs are intentionally not committed.

## PR1383 CI correction — 9 October 2026

At head `0fe770776`, workflow `37920849401` passed all 6,365 Java unit tests but
failed the Python scheduler inventory contract: `InquiryAcceptanceNoticeScheduler`
was absent from `replica-safety-components.json`. The catalog now records its
owner, runtime metrics (provided by the existing scheduled-method aspect),
committed database claim, durable pre-transport backoff, and manual reconciliation
for PREPARING/UNCERTAIN. This records the existing implementation without changing
its delivery guarantees or introducing a replica ceiling exception.

The MariaDB job failed while applying the new notice migration: its signed
`BIGINT` foreign key did not match the existing `session.id BIGINT UNSIGNED`.
The child key now uses `BIGINT UNSIGNED`; the H2 migration fixture uses the same
parent declaration. H2 compatibility mode did not expose the signedness mismatch,
so the real MariaDB 10.11 contract remains required. These CI failures are
attributable to this PR and are distinct from the earlier local inactivity-test
assertion. No test or assertion was skipped or weakened.

The replica-safety contract reproduced RED locally before the catalog change;
all 114 Python CI contracts pass after it. The identical CI MariaDB 10.11 schema-test selection passes: 92 tests, zero
failures/errors/skips, including all 16 ConsultantPictureDatabaseIT tests that
failed before the correction. The updated H2 migration test passes; Spotless
check and diff whitespace check pass. The isolated MariaDB container and its
anonymous volume were removed after verification. The separate authenticated
two-replica load step and published-head CI rerun remain required owner gates.

## PR1383 Java inventory and review follow-up — 9 October 2026

At published head `8c74db52b`, MariaDB passed, but the Java inventory contract
rejected the new catalog id because its independent closed required-component
set had not been extended. The first CI correction missed this second invariant.
The set now includes the actual `inquiry-acceptance-notice-retry` scheduler. Exact
set equality, unique ids, required fields and actionable signals remain enforced.

Focused review dispositions:

- Unsupported conversation types now call the actual `prepare` producer before
  asserting that no fact was saved; the preparation guard is exercised directly.
- The old PREPARING regression now calls actual `dispatch(231L)` rather than an
  empty scheduler query; it exercises the non-PENDING delivery guard and retains
  the later compensation assertion.
- Existing ownership tests explicitly forbid preparation for repeat IN_PROGRESS
  assignment and preparation cancellation for stale compensation.
- A real JPA concurrency regression synchronizes two workers after both read
  PENDING and before either claims the same notice. The externally proxied
  REQUIRES_NEW row-lock claim admits one worker; the test observes exactly one
  Matrix send call and the committed SENT/event id. Matrix transport is mocked;
  this does not claim an actual distributed server or recipient browser proof.

A scheduler-wide lease is a performance/scalability suggestion, not a missing
duplicate-send guard: the committed notice-row claim already excludes a second
sender. Both workers can mint a token before one loses the claim; adding a lease
may reduce that redundant work, but is not needed to preserve the observed
correctness boundary. No production delivery behavior was expanded.

Final focused verification: 45 tests pass across inventory, store, delivery,
ownership and real transaction classes, including the new concurrency test.
All 114 Python CI contracts and Spotless pass. The full final unit suite passes: 6,365 tests, zero failures/errors/skips; its
final Spotless check also passes. Commands and review dispositions are recorded
in `review-follow-up-receipt.json`. Published-head CI and Dev
acceptance remain separate required gates.
