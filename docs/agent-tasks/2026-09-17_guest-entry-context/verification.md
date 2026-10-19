# Public guest entry reads: verification

Issue: OpenResilienceInitiative/ORISO-UserService#1181. Parent: OpenResilienceInitiative/ORISO-Frontend#1216.

This additive slice provides read-only invitation context and checked name/avatar suggestions. It does not change existing clients, create accounts at Join, or alter session timers. Public exposure depends on OpenResilienceInitiative/ORISO-Helm#355.

## Local evidence (17 September 2026)

Java 21; source baseline `6f5ea55dacbeaa577bcde061b74ce47715f92ff8` (the observed Dev image revision). New context test compile failed before the API existed. Strict Matrix tests were authored before implementation but have no independently captured initial red run. The final targeted suite passed 98 tests; the full unit suite passed 4,675 tests with no skips or failures. Formatting is enforced by the Maven lifecycle.

```sh
./mvnw -B test -Dtest=GuestIdentityCatalogTest,GuestIdentitySuggestionServiceTest,GuestIdentityHttpTest,GuestUsernameAvailabilityTest,MatrixSynapseServiceStrictAvailabilityTest,AgencyInviteLinkContextTest,AgencyInviteLinkContextHttpTest,AgencyInviteLinkServiceTest,AgencyInviteLinkControllerTest
./mvnw -B test
./mvnw -B package -DskipTests
```

HTTP tests use real controllers, Spring Security and new business services, replacing external repositories, Matrix and identity-provider boundaries. They do not establish deployed nginx behavior. Tests also verify complete tenant-context restoration, no provisioning in reads, bounded request work, collision rejection, dependency failure and the unchanged legacy redeem behavior.

Independent source review found two problems (partial tenant-context restoration and mocked new availability logic in HTTP tests); both were corrected, retested and re-reviewed without remaining actionable findings.

## Catalogue provenance

`src/main/resources/identity/guest-name-catalog.json` is derived from Frontend `src/utils/anonName/data.ts` at deployed revision `cfb38b8fe6aa4d480bd3adaa8dae7c639c81e08f`. It retains existing animal labels, name lists and SVG asset keys; unused adjective tables are omitted. Usernames use the current ASCII credential alphabet. Non-ASCII scripts use existing English name/asset stems where transliteration yields an empty value. The visible identity is the actual username.

## Acceptance still open

No deployment of this change, real-browser frontend integration, real-provider failure test or account/queue side-effect readback has been performed. The later explicit Join slice must revalidate the selected identity and make retries idempotent; a suggestion does not reserve a name. Deploy the narrow ingress cap before making the new API publicly available. Existing clients keep their current paths until cutover.

## CI follow-up

Branch CI exposed a direct Matrix adapter dependency forbidden by the existing identity-module boundary. The local architecture test reproduced that failure. Guest availability now uses the existing `MatrixUserClient` outbound port; the adapter behavior is unchanged. All 100 Python CI contract tests pass after the correction. Targeted Java tests covering the port consumers and guest HTTP flow, formatting and package/repackage also pass.


## Review corrections: current local verification

The later review identified a real side effect hidden behind the former mocked token helper: strict Matrix lookup called `getAdminToken`, which can log in or bootstrap an admin on a cold cache. A new real-RestTemplate HTTP contract reproduced the unwanted `POST /_matrix/client/r0/login` before the correction (`/tmp/r3-readonly-matrix-red.log`). Strict lookup now consumes only `matrix.availabilityAdminAccessToken`, supplied through `MATRIX_AVAILABILITY_ADMIN_ACCESS_TOKEN`. Missing, revoked or rejected credentials fail with sanitized 503 without login/registration fallback. Six HTTP cases cover no-credential and configured-token GET sequences; existing strict status contracts no longer mock internal token acquisition. Legacy provisioning methods remain unchanged.

This requires deployment-owned credential provisioning and rotation before enabling the new UI. The credential has full Synapse admin privileges even though this code uses only GET. Helm PR #355 at `91045418e631bc7b57d0556d48346327abe090b9` provides an explicit existing-Secret reference and a rollout/rotation runbook; no secret value is stored in source, and no runtime secret or deployment has been changed.

Other review dispositions:

- Endpoint limiting is provided by companion Helm PR #355; its full-chart lint, rendering, package round-trip and route contracts pass. Runtime 429 and client-IP verification remain required.
- Availability diagnostics now log only the dependency exception class. The original exception is deliberately not retained as a cause because it can contain credentials. A red-then-green logging contract verifies the diagnostic and the absence of message/cause leakage (`/tmp/r3-review-contracts-red.log`).
- Catalogue tests now deterministically verify Cyrillic-label fallback to the selected avatar stem, exact truncation and separator cleanup.
- The exclusion-size regression uses valid usernames, so it cannot pass merely because username validation rejects the fixture.

Current verification: 135 targeted Java tests passed with zero failures/errors/skips (`/tmp/r3-review-contracts-green.log`); 109 Python CI/OpenAPI tests plus two subtests passed (`/tmp/r3-review-python.log`). Maven formatting and package/repackage passed (`/tmp/r3-review-package.log`). The earlier 4,675-test result belongs to the baseline before these corrections; it was not rerun for this review revision. Independent read-only review found no additional concrete auth/Secret-reference issue. These are local results, not live-provider or browser acceptance.
