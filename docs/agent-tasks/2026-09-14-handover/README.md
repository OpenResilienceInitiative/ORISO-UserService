# Recipient offers and ownership safety — developer handoff

Verified locally on 14 September 2026 after clean dev-backed reconstruction:
416 focused tests passed; package and Spotless passed. Independent Standards and Spec
source reviews found no remaining blocking extraction finding. This is not combined
integration, browser, deployment or mailbox acceptance.

Owner: **Shazia (`shazia-k`)**. Draft source handoff, not a finished Case Handover feature.

Reconstructed from retained local changes relative to `586bc459`, onto dev
`57570b8ef95fccc1018165cb961564dfb52142f1`. Includes previously untracked Java,
tests and migrations. Current dev's account-recovery changes are preserved.

## Included

- Recipient offer/list/accept/reject flow, session-scoped checks and redacted events.
- Ownership revision and operation IDs to prevent stale/replayed ownership changes.
- Assignment/removal paths routed through ownership safety; scoped mail handling.
- Migrations `0094_session_ownership_revision` and `0095_case_handover_period_operations`.

## Do not merge blindly with PR 1148

[PR 1148](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/1148)
was inspected at `e9ece33bde711367e3d9e696c181a2bb3aab0b47`, open, not in dev.
It supplies policy cache, consent/co-access and Matrix repair work; this branch does not
replace it. Reconcile `CaseHandoverService`, `CaseHandoverRequest`, its repository,
`EventNotificationService`, exception handler, controller/service/event tests and
`userservice-master.xml` before integrating both. Preserve both sets of behavioral tests.
The repeated migration number prefixes are not by themselves Liquibase ID collisions;
check actual IDs, include paths, ordering and SQL together. Do not rename applied history.

Full effective per-reason policy, standing-consent overrides, co-access lifecycle,
audit coverage and anonymous choice are not all delivered by this branch. Advice
co-access must not become ownership transfer. `ALWAYS` consent must not be bypassed
by a standing preference. This branch alone does not implement the requested
five-reason catalogue: the retained service still has `OTHER_EMERGENCY` defaults.
Reconcile with PR 1148 and TenantService PR 247, including the visible but disabled
legal-violation reason. Do not describe that catalogue as already delivered here.

## Local commands (Java 21)

```sh
./mvnw -B -Dtest=CaseHandoverControllerTest,ApiResponseEntityExceptionHandlerTest,ConsultantAdminServiceTest,EmailNotificationFacadeTest,AssignEnquiryFacadeTest,CaseHandoverServiceTest,EventNotificationServiceTest,SessionServiceAccessTest,SessionServiceTest,DeleteCaseHandoverRequestsForConsultantActionTest,DeleteDatabaseConsultantActionTest,CaseHandoverEmailNotificationTest,SessionOwnershipServiceTest test
./mvnw -B package -Dskip.unit-tests=true -Dskip.integration-tests=true
./mvnw -B spotless:check
```

Run `SessionOwnershipMariaDbIT` separately with its documented database configuration;
unit tests do not prove real database migrations or concurrency. After resolving PR1148,
repeat both suites and migration upgrade/rollback checks. Browser two-account acceptance,
actual mail receipt and mobile/desktop evidence remain deferred; no deployment is claimed.
