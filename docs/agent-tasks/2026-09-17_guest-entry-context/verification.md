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
