# UserService refactor integration

Feature request: [#887](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/887)

This branch is a review and combined-validation surface for the UserService
refactor. It is not a request to merge the complete branch into `pre-dev`, does
not authorize deployment, and does not prove PreDev runtime behavior.

## Verified base

- Branch: `feature/user-service-refactor`
- Base: `pre-dev`
- Base commit: `be15d12f6305f3370e626a0eec131293cceb5624`
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
| Identity second factor | [#882](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/882) | OTP and email verification use typed application values, bounded retries, and five stable low-cardinality operation tags |
| Identity email mutations | [#885](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/885) | Current-account and post-verification email writes use a focused output port with explicit no-op and provider-call bounds |
| Dead identity session close | [#886](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/886) | The unused command and both forwarding layers are removed with an executable zero-call boundary |

Every row is represented by a separate merge commit so the original PR head and
its review history remain traceable.

The #882 and #885 merges compose `IdentitySecondFactor` and
`IdentityEmailAddressUpdater` with the previously integrated authentication,
email-owner, role-read, and username-availability interfaces in
`KeycloakService`. Shared Spring test doubles implement all focused interfaces,
and the combined architecture contract retains every earlier boundary while
adding typed OTP/email-verification, bounded retries, and explicit email-write
call bounds. The #886 merge removes the unused session-close command while
preserving the active refresh-token logout flow.

## Deliberately not integrated yet

| State | PR | Reason |
| --- | --- | --- |
| Provider deployment gate | [#826](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/826) | Code is integrated for review, but PreDev shipment remains blocked until the TenantService batch endpoint is deployed and read back successfully |

The broad historical PRs [#753](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/753)
and [#757](https://github.com/OpenResilienceInitiative/ORISO-UserService/pull/757)
remain conflicting planning/evidence surfaces. They must not be merged on top of
the focused replay PRs.

## Combined local verification

Executed on 2026-07-28 with Temurin JDK 21:

- unit suite: 3,445 tests, 0 failures, 0 errors, 0 skipped;
- required integration/contract/E2E suite: 854 tests, 0 failures, 0 errors,
  9 environment-gated skips;
- CI and executable architecture contracts: 58 tests and 2 subtests passed;
- OpenAPI contract gate: 8 tests passed;
- package build and Spotless: passed;
- `git diff --check`: passed.

The dedicated MariaDB and Redis service-container gates remain required in
GitHub CI. A local database reset was not needed because the integration suite
creates and cleans isolated test databases.

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
   - ship existing Matrix, LiveService, Keycloak, and outbound measurement PRs;
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
