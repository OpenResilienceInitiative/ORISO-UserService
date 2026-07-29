# UserService refactor integration

Feature request: [#887](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/887)

This branch is a review and combined-validation surface for the UserService
refactor. It is not a request to merge the complete branch into `pre-dev`, does
not authorize deployment, and does not prove PreDev runtime behavior.

## Verified base

- Branch: `feature/user-service-refactor`
- Base: `pre-dev`
- Base commit: `0ea5ba3206ad3a59c8a91e09d8b3d626ded438f4`
- Product target: Matrix-only chat through the ORISO frontend, the
  ORISO-controlled Element Call/MatrixRTC fork, and LiveKit
- Rocket.Chat and Jitsi: complete removal targets, never fallback transports

## Included reviewable PR heads

| Section | PR | Result in this integration branch |
| --- | --- | --- |
| Tenant dependency bound | [#826](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/826) | Batched tenant enrichment without changing the public pagination contract |
| Identity input direction | [#883](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/883) | Consultant role decisions enter through the application-owned identity input |
| Identity role reads | [#878](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/878) | Full role-set reads use a focused output port and one bounded provider read |
| Identity email ownership | [#879](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/879) | Provider-neutral typed owner lookup replaces transport maps in application code |
| Identity authentication | [#880](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/880) | Login, logout, and verification use a focused provider-neutral port |
| Username availability | [#881](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/881) | Four current consumers use a focused availability port |
| Username application input | [#833](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/833) | Web adapters call the application-owned identity input; `IdentityManager` delegates to the focused availability output |
| Identity second factor | [#882](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/882) | OTP and email verification use typed application values, bounded retries, and five stable low-cardinality operation tags |
| Identity email mutations | [#885](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/885) | Current-account and post-verification email writes use a focused output port with explicit no-op and provider-call bounds |
| Identity profile reads | [#891](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/891) | Authenticated user-data mapping uses a focused lookup and a five-field provider-neutral profile |
| Identity role writes | [#894](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/894) | Consultant role writes use a focused batch port, deduplicate roles and bound provider reads, writes, visibility checks and retries |
| Identity profile writes | [#895](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/895) | Admin and consultant profile mutations use a focused five-field provider-neutral port with explicit lookup, availability-check, update and retry bounds |
| Dead identity session close | [#886](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/886) | The unused command and both forwarding layers are removed with an executable zero-call boundary |
| Dead LiveService transport | [#902](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/902) | The unreachable transport and retry path are removed; the deprecated route is a dependency-free `410 Gone` tombstone |

Every row is represented by a separate merge commit so the original PR head and
its review history remain traceable.

The #881 and #833 merges compose the username path as web adapter →
`IdentityManaging` → `IdentityUsernameAvailability`, so web code cannot bypass
the application boundary while the broad provider client remains free of the
availability read. The #882 and #885 merges compose `IdentitySecondFactor` and
`IdentityEmailAddressUpdater` with the previously integrated authentication,
email-owner, role-read, and username-availability interfaces in
`KeycloakService`. Shared Spring test doubles implement all focused interfaces,
and the combined architecture contract retains every earlier boundary while
adding typed OTP/email-verification, bounded retries, and explicit email-write
call bounds. The #891 merge removes authenticated profile reads and Keycloak
representations from the broad client and composes its lookup into the same
shared Spring test doubles without weakening any earlier focused port. The #894
merge moves consultant role writes out of the broad client, batches missing
roles in one add operation per attempt, skips empty and case-equivalent role
sets, and retains bounded visibility and admin-session retries. The #895 merge
moves admin and consultant profile writes out of the broad client and keeps
username, email, tenant ID, first name and last name provider-neutral. An
unchanged email skips the availability search; a changed email performs one
search before the single update; the adapter does not retry profile writes. The
#886 merge removes the unused session-close command while
preserving the active refresh-token logout flow. The #902 merge deletes the
unreachable LiveService dependency while keeping Matrix push and durable
timeline notifications independent of partial persistence and cache failures.

## Deliberately not integrated yet

| State | PR | Reason |
| --- | --- | --- |
| Provider deployment gate | [#826](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/826) | Code is integrated for review, but PreDev shipment remains blocked until the TenantService batch endpoint is deployed and read back successfully |

The broad historical PRs [#753](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/753)
and [#757](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/757)
remain conflicting planning/evidence surfaces. They must not be merged on top of
the focused replay PRs.

## Combined local verification

Executed on 2026-07-29 with Temurin JDK 21:

- unit suite: 3,420 tests, 0 failures, 0 errors, 0 skipped;
- required integration/contract/E2E suite: 854 tests in 82 reports, 0 failures,
  0 errors, 9 environment-gated skips;
- CI and executable architecture contracts: 68 tests passed;
- OpenAPI contract gate: 8 tests passed;
- focused username application/output composition: 38 Java tests passed;
  focused Keycloak, batch-role and profile-write composition: 121 Java tests
  passed; all 20 focused module-boundary tests passed within the 68-test CI
  suite;
- focused Matrix push, durable-notification and LiveService-removal composition:
  154 tests passed;
- local two-replica mixed-read proof: 1,400 requests at concurrency 32, 0
  failures, 95.38 ms overall p95 and 633.63 requests/second;
- dependency proof for those reads: 900 consultant-profile reads produced
  exactly 900 AgencyService calls, 8.87 ms mean and 101.91 ms maximum outbound
  latency, 288.89 response bytes per call on average, and no threshold
  violations;
- authenticated two-replica write proof after the CI port-allocation regression
  fix: 80 concurrent upserts and both cross-replica reads passed, followed by 12
  writes and both reads after one replica restart, with one canonical row;
- package build and Spotless: passed;
- `git diff --check`: passed.

Compared with the preceding #886 integration head, the net reduction of 33
unit tests and two integration tests is the removal of tests that exercised the
deleted LiveService transport and forwarding controller. The replacement
contracts cover the dependency-free `410 Gone` tombstone, Matrix-recipient
push, durable timeline delivery and partial-failure isolation.

The dedicated MariaDB and Redis service-container gates remain required in
GitHub CI. A local database reset was not needed because both the integration
suite and the exact-head load proof create and clean isolated databases. The
load runner also removed both JVMs, both disposable dependency containers and
the AgencyService stub; its three local listening ports were free after the
run.

## Problems addressed

- process-local and scheduler state was too easy to mistake for replica safety;
- repeated external effects and broad outbound clients made failure bounds hard
  to reason about;
- dependency fan-out made successful requests noisy and slower to diagnose;
- provider DTOs and map keys leaked into application code;
- source, CI, merge, deployment, and runtime proof were being conflated;
- a large number of parallel PRs made the combined architecture difficult to
  review.

## Expected benefits

- bounded TenantService and identity-provider calls reduce dependency fan-out
  and make latency budgets more predictable;
- focused provider-neutral ports reduce the number of application classes that
  change when Keycloak transport details change;
- executable module contracts stop broad identity dependencies from silently
  returning;
- one integration branch exposes cross-PR conflicts before shipment;
- smaller approved deployment sections reduce rollback scope and reviewer load;
- attributable traces and explicit call bounds shorten SigNoz diagnosis after a
  deployment;
- the service remains at the explicit supported replica ceiling until the
  scheduler and runtime proofs are complete.

No exact deployment-time reduction is claimed before PreDev measurements. The
expected operational gain is less rework and faster diagnosis, not a fabricated
build-duration number.

## Proposed PreDev shipment sections

1. **Safety baseline and test truth**
   - confirm the one-replica ceiling;
   - require unit, integration, MariaDB, Redis, OpenAPI, and architecture gates.
2. **Bounded observability and outbound delivery**
   - ship the Matrix, Keycloak, outbound measurement and dead-LiveService
     removal PRs;
   - verify traces and redaction in SigNoz after deployment.
3. **Idempotent schedulers and lifecycle effects**
   - ship one scheduler/notification claim at a time;
   - prove two-instance behavior and crash boundaries before scale-out.
4. **Focused identity, tenant, and application ports**
   - ship the original atomic PRs represented in this branch;
   - deploy the TenantService provider before its UserService consumer.
5. **Legacy and runtime simplification**
   - keep Matrix as the only chat transport;
   - complete Rocket.Chat/Jitsi removal without fallback wiring.
6. **Controlled PreDev shipment**
   - require human approval, exact-head CI, ordered merge, deployment readback,
     SigNoz checks, and rollback notes between sections.

The original atomic PRs remain the shipment units. This integration branch is
the combined code-review and validation surface only.
