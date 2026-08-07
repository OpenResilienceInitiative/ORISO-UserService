# UserService stability, dependency measurements and module decision

Target branch: `pre-dev`

Suite counts in this record are generated, not written down — see
[Reproducible stability result](#reproducible-stability-result). A date stamp
here would have the same problem, so freshness comes from the CI run that
produced the numbers.

## Reproducible stability result

The historical full-suite baseline contained 4,707 tests, 28 failures, 704 errors
and 10 skipped tests. The failures were not one defect: they combined stale
Spring Security assumptions, test-database replacement, Spring Boot 4 / Jackson
3 migration gaps, incomplete external-service test doubles, stale chat migration
expectations and two production regressions.

The counted classification is machine-readable in
[`user-service-historical-failure-classification.json`](user-service-historical-failure-classification.json)
and protected by an executable CI contract. Its failure clusters sum exactly
to 28: 19 obsolete security assertions, two Actuator contract mismatches, one
Rocket.Chat configuration expectation, three session-locking test doubles and
one case each for a Jackson request fixture, the public-consultant test double
and a stale chat aggregate assertion. Its error clusters sum exactly to 704:
637 replacement-H2 datasource failures, 22 Spring Plugin/HATEOAS ABI errors
and 45 initial Spring context-threshold cascades. The artifact preserves the
15-suite breakdown behind those 45 errors and does not invent a more specific
original exception where the retained report did not contain one.

After repairing those clusters the suite is green.

The current execution counts are deliberately not recorded here. They are a
measurement, not a decision, and every branch that adds a test changes them —
which made this file conflict on each merge and forced a hand reconciliation
that is easy to get wrong. `scripts/ci/suite-inventory.py` derives them from
the Surefire and Failsafe reports instead, and both test jobs publish the
result to their CI run summary. For the combined view locally:

```
./mvnw verify
python3 scripts/ci/suite-inventory.py
```

What is enforced rather than described:

- `scripts/ci/run-required-integration-tests.sh` owns the complete `*IT` suite,
  requires at least 75 reports and 830 executed tests, and fails on any failure
  or error;
- a required CI guard rejects newly disabled or ignored tests;
- the MariaDB schema and statistics contracts and the Redis availability
  contract run as their own required jobs, so they are dedicated environment
  proofs rather than part of the primary inventory.

The historical 4,707 figure is the raw failing discovery run, not the same test
inventory with failures simply subtracted. After the original repair work, the
last pre-cutover inventory recorded 3,782 unit and 940 integration executions,
or 4,722. Those figures are frozen reference points and do not move.

The Matrix-only cutover then reduced both the executable product and its test
inventory. The source diff for that pre-cutover-to-current interval deletes 40
obsolete test classes and adds 29 Matrix-only contract classes. Thirty-three of
the 40 deleted classes cover the removed Rocket.Chat, legacy
chat/import/message, or obsolete session/conversation E2E paths. Because JUnit
execution counts include parameterized and dynamic cases, class counts do not
map one-to-one to the execution delta. This is intentional scope removal plus
replacement coverage, not unexplained test quarantine.

Nineteen stale security tests were removed. They asserted that safe `GET`
requests or the explicitly CSRF-exempt public registration endpoint require a
CSRF token, which contradicts the service's security contract. No failing test
is skipped or quarantined.

The integration contract starts from a clean build and additionally checks that
the critical E2E reports are present, so a green run cannot mean a silently
shrunken suite. The previous three-test required subset and the non-blocking
legacy quarantine were removed. On the current Matrix-only `pre-dev` baseline,
the four remaining `NewEnquiryEmailSupplierTest` log assertions run normally.
The Matrix cutover deleted `NewMessageEmailSupplierTest`; this replay
deliberately does not restore that legacy path. The 830-test floor is the
Matrix-only figure; the older 900-test floor included deleted Rocket.Chat-only
tests. The environment-gated Redis and MariaDB jobs run on the branch,
pull-request and publish workflows.

The first clean Ubuntu run exposed three portability defects that a warmed local
workspace had hidden. Each Spring test context now owns a unique H2 database so
an evicted `create-drop` context cannot remove another cached context's schema.
Timestamp preservation assertions allow only H2's sub-microsecond rounding.
The Actuator integration test checks liveness rather than aggregate dependency
health; Redis and MariaDB availability remain independently required contracts.

Making the MariaDB contract required exposed and repaired one real production
schema drift: `ReservedPublicSlug.active` declared the SQL default as part of
Hibernate's expected column type. The entity now expects `TINYINT`, while the
default remains correctly owned by Liquibase. A fresh database applies all 91
changesets and passes Hibernate validation.

## Dependency and call measurements

The source inventory contains 13 generated API-controller factories, six
client/configuration helpers and 71 direct `RestTemplate` call sites. The
runtime dependency set is:

| Boundary | Dependencies | Transport |
| --- | --- | --- |
| Identity | Keycloak, identity extensions | Keycloak client + HTTP |
| Chat | Matrix only | HTTP long-poll + HTTP |
| ORISO services | Agency, Tenant, Consulting Type/Topic/Application Settings, Appointment, Message, Mail, Live | HTTP |
| State/event infrastructure | MariaDB, Redis, RabbitMQ | JDBC, Redis, AMQP |

All `RestTemplateBuilder` clients now emit:

- `userservice.outbound.http.calls`: call attempts by dependency host, method and
  coarse outcome;
- `userservice.outbound.http.latency`: latency with the same low-cardinality
  dimensions and finite 10 ms to 60 s SLO buckets, so p95 can be derived in
  SigNoz instead of having only a `+Inf` bucket;
- `userservice.outbound.http.payload`: exact request bytes and response bytes
  when `Content-Length` is available;
- `userservice.outbound.retries`: explicitly scheduled Keycloak and Matrix
  retries by fixed dependency and operation tags.

Paths, query values, IDs and exception text are never custom metric tags.
Spring Boot's standard `http.client.requests` remains available as an
independent cross-check, but now uses a bounded observation convention:
untemplated URLs are grouped as `uri=untemplated`, and URI templates retain
only their query-free path template. High-cardinality `http.url` trace
attributes retain only the dependency origin (scheme, host and explicit port),
never a path, query, fragment or user-info value. Every UserService-owned
`RestTemplate`, including the Matrix long-poll and Keycloak extension clients,
installs this convention explicitly and idempotently instead of relying only on
Spring Boot builder auto-configuration.

A read-only PreDev query against the currently deployed pod exposed why this
additional guarantee is necessary: 27 Matrix client spans produced 26 distinct
raw URI values because sync cursors remained in query strings, and historical
Keycloak traces contained dynamic test-identity path segments. No raw values
are copied into this record. This is current-runtime evidence, not proof of this
branch being deployed. After merge and rollout, the aggregate-only verification
query must show zero raw-query URI classes and origin-only `http.url` values.
The Java `HttpClient` used by LiveService is not covered by the payload
interceptor; its higher-level retry paths are covered by the explicit retry
counter. This remains a known measurement boundary, not an implied zero.

Keycloak's own RESTEasy admin-client transport is covered separately by
`KeycloakAdminClientTransport`. It preserves one pooled singleton client (50
connections), bounds connect and connection-checkout waits at three seconds and
read waits at ten seconds, and publishes the same low-cardinality call, latency
and known payload measurements as the Spring HTTP clients. Apache's hidden
transport retries are disabled, so one measured transport attempt equals one
actual HTTP attempt; bounded application retries remain explicit through
`userservice.outbound.retries`. The behavioral transport regression proves a
slow response timeout, pool-exhaustion timeout, one failed HTTP attempt instead
of Apache's previous four attempts, and eight concurrent admin reads sharing
one token acquisition.

Current `pre-dev` has removed the Rocket.Chat production adapter,
configuration, DTOs, database/wire fields and optional MongoDB access.
Matrix/Synapse is the sole messaging backbone, the ORISO frontend remains the
product surface, and LiveKit plus the controlled Element Call/MatrixRTC fork is
the target calling stack. Remaining Rocket.Chat names are limited to the
forward-only removal changelogs, removal/migration contracts and historic
architecture diagrams; they are not a supported runtime or fallback. Jitsi
removal is coordinated outside this UserService-only stability change across
Frontend, call/appointment contracts and deployment.

### Live PreDev baseline before this change

A read-only SigNoz/ClickHouse audit on 2026-07-25 proved that the running
UserService pod was exporting both metrics and traces through the cluster OTel
collector. In an approximately 40-minute active window, the standard client
metric showed:

| Dependency/operation | Calls | Mean latency |
| --- | ---: | ---: |
| Matrix GET 2xx | 138 | 19.36 s |
| Matrix GET 403 | 26 | 3.9 ms |
| Matrix POST 2xx | 9 | 176.1 ms |
| Matrix PUT 2xx | 3 | 262.4 ms |
| Tenant GET 2xx | 6 | 47.4 ms |
| Consulting Type GET 2xx | 8 | 16.2 ms |
| Keycloak GET 2xx | 5 | 19.4 ms |
| Agency GET 2xx | 2 | 36.6 ms |

The Matrix GET mean is dominated by the expected sync long-poll and must not be
read as ordinary request slowness. The audit also found 47 Matrix GET series:
the standard `uri` label included the changing Matrix `since` query parameter.
That real cardinality defect motivated the bounded observation convention
above. Existing standard histograms exposed only the `+Inf` bucket, which
motivated the explicit finite latency buckets.

The audited pod predates this branch. Therefore its live data proves the OTel
pipeline and supplies a baseline, but it does not prove the new
`userservice.outbound.*` metrics, payload sizes, retry counters or cardinality
repair. Those require the branch image to be deployed and queried again.

### Live PreDev follow-up after the `pre-dev` merge

The 2026-07-26 read-only follow-up kept build, deploy and runtime evidence
separate:

- merge commit `730a9323` published the UserService `pre-dev` image with digest
  `sha256:16534c4d5b0cf8d98b58e164c75bc1ee0320e4597fe28181ab56eee198fe1cfb`;
- the running PreDev deployment still used the older digest
  `sha256:11c0a03cd903d387a6cc229412ac91e1a99b33cb68f1d276e09613d4c4c479e2`;
- no `userservice.*` metric metadata existed in the live SigNoz store.

The merge and image publication are therefore confirmed, but deployment and
the new custom-metric runtime proof remain open.

Approximately 24 hours of traces from that older running image supplied this
baseline:

| Service/client operation | Spans/calls | Errors | p95 latency |
| --- | ---: | ---: | ---: |
| UserService, all spans | 15,732 | 33 | 25.38 ms |
| Matrix POST | 877 | 0 | 47.64 ms |
| Matrix DELETE | 660 | 0 | 26.27 ms |
| Matrix GET | 474 | 1 | about 30 s |
| Matrix PUT | 8 | 0 | 287.16 ms |
| Tenant GET | 14 | 10 | n/a |
| Keycloak GET | 9 | 0 | 16.23 ms |
| Consulting Type GET | 9 | 0 | 20.86 ms |
| Agency GET | 4 | 0 | 28.89 ms |

Of the Matrix GET calls, 462 were expected `/sync` long-polls. Their latency is
not ordinary request slowness. The Tenant errors were 404 responses in a
technical/global context. That context has no tenant-specific branding, so the
canonical tenant-template supplier now returns only the generic application URL
without calling TenantService or ApplicationSettingsService. A unit regression
test proves that both outbound boundaries remain untouched. Runtime
confirmation still requires the repaired branch image to be merged, deployed
and traced.

### Anonymous-deletion repeat loop

Trace grouping identified one concrete chatty-call cause in
`deleteUserAnonymousScheduler.performDeletionWorkflow`:

- six scheduler runs made 1,510 successful Matrix client calls;
- each run averaged about 252 Matrix calls and reached a maximum of 272;
- every root workflow then failed after about 7.5 seconds while the secondary
  error-notification path tried to resolve a tenant template.

The deletion method wrapped both irreversible Matrix actions and database
cleanup in one transaction. When notification failed after the actions, the
exception rolled back database cleanup, so the next scheduler run repeated the
already completed external calls.

The notification step is now best-effort: its runtime failure is logged without
workflow identifiers instead of escaping the transaction. The technical mail
context also no longer performs the TenantService lookup that caused the
observed notification failure, which removes the most frequent trigger.

Both suites, the focused supplier test and the formatting gate pass; the
per-run counts are in the CI summary rather than repeated here.

#### Measured limit of this repair

Catching the notification failure is not sufficient on its own when the
workflow error originates inside the database delete. Reproduced locally in
`DeleteUserAnonymousSchedulerIT`: with the preceding session deletes flushed
and the user detached, `userRepository.delete` takes Hibernate's merge path and
raises

```
org.hibernate.ObjectNotFoundException: No row with the given identifier exists
  for entity [de.caritas.cob.userservice.api.model.Session with id ...]
  at org.hibernate.type.EntityType.replace(EntityType.java:334)
```

which matches the PreDev stack. `DeleteDatabaseAskerAction` catches it and
records a workflow error, the notification then fails and is caught here as
intended — but Hibernate has already marked the transaction rollback-only, so
the commit still fails with `UnexpectedRollbackException` and nothing is
retained. Stubbing the repository to throw reproduces the shape of that failure
but not its consequence, because only a genuine failure poisons the persistence
context; the test therefore provokes the real exception.

Making the deletion commit independently of that poisoned context requires its
own transaction boundary. That boundary now exists, described below.

#### Per-user transaction boundary

Selection, deletion and notification each own their scope:

- `AnonymousUserDeletionCandidates` reads in a read-only transaction and returns
  user **ids**, not entities. An entity handed across a transaction boundary
  would be detached in the next one, which is what made the delete take
  Hibernate's merge path over an already-initialized session collection in the
  first place. Loading the user inside the deleting transaction removes that
  path entirely.
- `AnonymousUserDeletionUnit` deletes exactly one user under
  `Propagation.REQUIRES_NEW` and commits when it returns.
- `AnonymousUserDeletionBatch` holds no transaction. It catches a failed
  per-user commit, records it as a workflow error and continues with the
  remaining users.
- `DeleteUserAnonymousService` notifies after the batch, outside every deletion
  transaction.

A user whose own transaction is poisoned still cannot be retained — that
transaction is doomed by definition — but it is now the only one lost, and it
is reported rather than silent. Proven in `DeleteUserAnonymousSchedulerIT` with
a genuine `DataIntegrityViolationException`: a leftover `user_agency` row makes
one user's delete violate the restricting foreign key, and the other user in
the same batch is still deleted and committed.

The selection is also filtered to `RegistrationType.ANONYMOUS`. Without that
filter this workflow, configured under `user.anonymous.deleteworkflow.*`, also
selected registered system accounts such as the per-tenant
`group-chat-system-*` users.

## Chatty-call reductions

- The Matrix-only cutover physically removed the Rocket.Chat adapter, credential
  provider, MongoDB client and scheduler. Negative architecture contracts
  prevent those production paths from returning.
- Anonymous live-chat queue visibility is topic-only and therefore avoids an
  AgencyService lookup merely to resolve consulting-type visibility.
- When the consultant-agency batch read is empty or fails, the local fallback
  performs no per-agency AgencyService retries and loads the lowest known
  consulting type for all agency IDs in one grouped session query. For `N`
  agencies, this changes the failure path from `1 + N` outbound calls plus `N`
  local queries to one outbound batch call plus one local query while
  preserving IDs, topic assignments and configured fallback values. This is
  local code/test evidence until the branch is merged, deployed and measured
  on PreDev.
- Appointment deletion uses one conditional database `DELETE` and its affected
  row count. It preserves the 404 contract without a read-before-delete round
  trip.
- Authenticated user-data profile lookup now uses one focused
  `IdentityProfileLookup` call and exposes only five provider-neutral fields
  across the application seam: ID, username, first name, last name and email.
  The Keycloak adapter still performs exactly one user-resource resolution and
  one representation read, with no retry. Provider not-found becomes absence;
  other provider failures retain the token-based facade fallback. This bounds
  the internal payload but does not claim a smaller Keycloak network response.
- Consultant role-set validation reads the user's complete realm-role list once
  and performs the requested-set intersection in-process. The code-level bound
  is therefore zero identity calls for an empty role set and exactly one for a
  non-empty set, instead of up to one `userHasRole` request per candidate role.
- Consultant role assignment uses the focused `IdentityRoleUpdater` batch
  port. Empty requests cause no identity traffic. Each non-empty attempt makes
  one assigned-role list request, deduplicates the requested roles and skips
  already assigned roles. For `M` missing roles, the adapter performs `M`
  targeted role-representation lookups, one batch add and one complete-set
  visibility read; visibility may be read at most three more times under the
  existing bounded retry policy. An unauthorized admin session refreshes once
  and retries the complete idempotent attempt once. This replaces one complete
  add-and-visibility sequence per requested role while documenting the
  remaining provider lookups instead of claiming a constant total call count.
- Admin and consultant profile mutations use the focused
  `IdentityProfileUpdater` with exactly five provider-neutral values: username,
  email, tenant ID, first name and last name. Every mutation resolves the target
  once and reads its current representation once. An unchanged email performs
  no availability search and one update; a changed available email performs one
  availability search and one update; a changed unavailable email performs one
  availability search, preserves the conflict response and performs no update.
  The adapter applies no automatic retry, so provider failures retain their
  existing propagation behavior.
- The unused identity session-close command was removed from the broad output
  port, the Keycloak facade and its authentication collaborator. Repository-wide
  tracing found no production consumer, so the removed path has an exact
  zero-call bound. The active refresh-token logout flow remains unchanged.

The runtime metrics above are the gate for further optimization: prioritize a
dependency only when PreDev shows high calls per request, payload volume or p95
latency. This avoids speculative batching and caches without an invalidation
model.

## Internal module boundaries

The target is Matrix-only. The Rocket.Chat production adapter, configuration,
DTOs and optional MongoDB access have been removed; retained names are limited
to forward-only changelogs, removal contracts and historic evidence. Video
calling belongs to the ORISO-controlled Element Call/MatrixRTC fork with
LiveKit, without Jitsi.

The intended dependency direction is:

```mermaid
flowchart LR
  HTTP[Web controllers and delegates]
  IN[Input ports]
  APP[Managers, facades and workflows]
  OUT[Output ports]
  ADAPTERS[Matrix, Keycloak, repositories and generated clients]

  HTTP --> IN --> APP --> OUT --> ADAPTERS
```

The current state is deliberately tracked per domain instead of describing the
whole codebase as modular:

| Module | Enforced seam | Remaining debt |
| --- | --- | --- |
| Identity/profile | User web entry points use `AccountManaging` and `IdentityManaging`; `service.identity` and `service.user` cannot import concrete identity/chat adapters. `IdentityClient` returns application-owned `CreatedIdentity` for user creation. Magic-link exchange returns the separate provider-neutral `api.model.identity.IdentitySession`; only Keycloak adapters own grant fields and provider response parsing. Profile email propagation uses the `MessageClient` port. Registration-time dummy-email replacement uses the focused provider-neutral `IdentityDummyEmailUpdater` port. Strict deletion and best-effort rollback use the focused provider-neutral `IdentityAccountRemover` port. Realm-role reads use the focused `IdentityRoleLookup` port; consultant role writers use the focused batch `IdentityRoleUpdater` port and the broad identity command client no longer owns role ensuring. Account, asker, consultant and anonymous-user deactivation use the focused provider-neutral `IdentityDeactivator` port. Password writers depend on `IdentityPasswordUpdater`. Authenticated profile reads use `IdentityProfileLookup` and a five-field provider-neutral `IdentityProfile`. Admin and consultant profile writes use `IdentityProfileUpdater` and a five-field provider-neutral `IdentityProfileUpdate`; the broad identity command client no longer accepts web-layer profile DTOs. OTP credential management and email verification use the focused `IdentitySecondFactor` output port and provider-neutral values; generated Keycloak DTOs and string-keyed maps stay inside the adapter. | Identity creation still accepts the web-layer `UserDTO`, and other identity operations on the broad port remain to be narrowed further. |
| Admin | Chat account creation/update, room checks and group membership use `MatrixUserClient`, `MessageClient` and transport-neutral member IDs; identity creation consumes the neutral `CreatedIdentity` result; `api.admin` cannot import concrete Matrix adapters. | The large admin controller still composes many services, and identity creation still receives the web-layer `UserDTO`. |
| Session/consultant | Room provisioning and assignment depend on `SessionRoomGateway` and `SessionAssignmentChatGateway`; their adapters own Matrix DTOs, credentials and failure policy. Both protected application packages have executable import boundaries. | Session/consultant orchestration remains broad even though the Rocket.Chat transport has been removed. |

`tests/ci/test_module_boundaries.py` prevents the stabilized user web slices
from reverting to concrete application/chat services, prevents user web
mappers from importing the outbound identity client, and prevents the
`service.session` application package from importing concrete Matrix adapters. It also
prevents the Identity/Profile packages and the Admin module from importing
their protected concrete chat adapters. The separate removal contract prevents
Rocket.Chat production packages, configuration, DTOs and schema fields from
returning. A dead-surface contract prevents the unused identity session-close
command from returning on the broad port or either Keycloak wrapper. The
appointment deletion repair stays behind `Organizing` and `AppointmentRepository`.

A dedicated magic-link boundary contract prevents the application service and
both web entry points from importing Keycloak transport types. It also prevents
the public magic-link response DTO from depending on an outbound-port package.
A dedicated profile-read contract keeps user-data facades off the broad
identity client, removes profile lookup from that client and requires shared
Spring identity mocks to provide the focused port.
The role-read contract keeps full realm-role reads behind the focused
`IdentityRoleLookup` port and prevents per-candidate role checks from returning
to consultant-agency validation.
The role-write contract requires both active consultant role writers and shared
Spring identity mocks to use the focused batch `IdentityRoleUpdater` port, and
prevents singular role ensuring from returning to the broad identity client.
The profile-write contract keeps web and Keycloak transport types out of the
focused `IdentityProfileUpdate` value, requires both active application writers
and shared Spring mocks to use the `IdentityProfileUpdater` port, and removes
profile writes from the broad identity client.

Registration-time dummy-email replacement now retains only username and tenant
metadata in a provider-neutral value. The Keycloak adapter computes the dummy
address, resolves the target identity once and performs one update. The former
asker-import consumer no longer exists on the Matrix-only baseline; current-user
email deletion remains on the existing email-address operation.

Identity account removal now has explicit provider-call bounds. A normal
removal resolves the target once and calls remove once. An unauthorized removal
refreshes the admin session once and retries the complete lookup/remove
operation at most once. Provider not-found handling remains idempotent,
provisioning rollback remains best-effort and strict deletion sequencing is
unchanged.

The focused password-write boundary covers admin provisioning, consultant
provisioning and imports, user registration and self-service password reset. A
write resolves the target identity once, performs one provider reset and has no
automatic retry. Password-reset token restoration and provisioning rollback
remain application policies; provider credential DTOs and password-policy error
translation remain adapter concerns.
Identity deactivation now has an explicit one-user call bound: the Keycloak
adapter resolves the users resource and user once, reads one representation and
performs at most one update, with no hidden or application retry. The anonymous
deactivation action keeps its existing best-effort error handling; the account,
asker and consultant deletion flows remain strict and preserve their existing
ordering around lifecycle and persistence changes.

A dedicated second-factor boundary contract keeps OTP and email-verification
operations out of the broad `IdentityClient` contract. Each of the five
operations performs exactly one provider request on its normal path. An initial
unauthorized response can trigger at most one token refresh and one repeated
provider request, for a maximum of two attempts. Retry measurements use five
fixed low-cardinality operation tags (`otp-fetch`, `otp-setup`, `otp-delete`,
`email-verification-start` and `email-verification-finish`) and contain no
username or other user data. The application passes the encoded username
through the focused port; the Keycloak adapter owns provider-specific decoding,
while the verified email mutation continues to use the decoded username.
External APIs, schemas and configuration remain unchanged, and this refactor
does not reduce the number of second-factor provider calls. These are
source-and-local-test guarantees until the branch is merged, deployed and
verified on PreDev.

This is a ratcheted incremental modularization, not a claim that all three
domains are already isolated. Rocket.Chat removal is complete in production
source, and identity create-user results are provider-neutral via `CreatedIdentity`. The next safe sequence
is further non-authentication identity transport cleanup, then the Admin controller
composition boundary, then smaller Session orchestration boundaries. Each step
must add a failing boundary contract before moving dependencies.

## Scheduler replica safety

The hourly enquiry-notification workflow acquires the global, durable
`scheduled_task_claim` named `enquiry-notification` before it reads sessions,
agencies or consultants and before it sends mail. The claim lasts 30 minutes,
shorter than the hourly schedule. A losing replica exits without downstream
work; an expired claim can be renewed.

The regression proof starts with the unfixed behavior: two concurrent service
instances produced two mail batches. The fixed focused suite verifies one
batch. `ScheduledTaskClaimMariaDbIT` then executes both the concurrent initial
insert and the concurrent expired-claim renewal against MariaDB 11.0.6 with two
separate transactions. Each race has exactly one winner; MariaDB's initial
insert race is handled as an InnoDB deadlock/constraint conflict followed by a
read of the winning active claim. The required MariaDB workflow runs this test
together with schema drift, statistics projection and the other replica
contracts.

The anonymous-user deactivation scheduler uses the same lease primitive with
the claim name `anonymous-user-deactivation` and a 30-minute duration below its
hourly interval. A losing replica exits before setting the technical tenant
context or invoking the lifecycle service. Its two-instance regression test
first reproduced two workflow calls and now observes exactly one.

The anonymous-user deletion scheduler likewise claims
`anonymous-user-deletion` for 30 minutes before setting the technical tenant
context or starting the irreversible deletion workflow. The red two-instance
proof observed both replicas executing the workflow; the fixed regression
observes one winner and no downstream work from the losing replica.

## Microservice decision

Decision: keep UserService as a modular monolith for now.

The suite demonstrates extensive shared transactional behavior and the service
already coordinates at least ten synchronous service boundaries. Splitting a
module before runtime measurements would add more network calls, partial-failure
states and contract deployment sequencing while the current internal ports
already provide the needed seam.

Reconsider extraction only when PreDev telemetry supplies all of:

1. a cohesive module with no shared-database writes across its boundary;
2. independently meaningful ownership and release cadence;
3. sustained call volume or latency that cannot be solved within the process;
4. an explicit versioned contract and failure/retry policy;
5. load and E2E evidence for both sides of the proposed split.

## Load and regression use

Run a bounded single-endpoint smoke against a deployed environment:

```bash
python3 tests/load/user_service_load_smoke.py \
  --base-url https://userservice.example \
  --path /actuator/health \
  --requests 500 \
  --concurrency 20 \
  --max-error-rate 0 \
  --max-p95-ms 1000
```

Protected endpoints can receive repeated `--header 'Name: value'` arguments.
The script reports request count, error rate, response bytes, throughput and
mean/p50/p95/max latency and exits non-zero when either threshold is exceeded.

For a weighted workload, pass `--scenario`. The exit threshold applies to the
overall result and every named operation, so a slow low-weight endpoint cannot
hide behind a healthy aggregate. The runner also requires at least one complete
weight cycle.

The reproducible local seeded read proof is:

```bash
bash scripts/load/run-seeded-public-read.sh
```

The runner starts a real UserService testing-profile process with
`UserServiceDatabase.sql` in an isolated H2 database, starts the deterministic
AgencyService batch-read stub, waits for liveness, warms every operation, runs
`tests/load/scenarios/seeded-public-read.json`, and cleans up both processes.
Defaults are 1,400 requests, concurrency 32, zero tolerated failures and a
1,000 ms p95 ceiling. Request count, concurrency and ceiling can be overridden
with `USERSERVICE_LOAD_REQUESTS`, `USERSERVICE_LOAD_CONCURRENCY` and
`USERSERVICE_LOAD_MAX_P95_MS`.

The six operations exercise three seeded consultant profiles, their
agency/topic relations, two agency-language aggregations, the generated
AgencyService HTTP client contract, security/controller/JPA/mapping paths, and
a small liveness control.

Local healthy-dependency proof on 2026-07-25:

| Operation | Requests | Failures | Response bytes | p95 |
| --- | ---: | ---: | ---: | ---: |
| Consultant profile — addiction | 400 | 0 | 148,400 | 128.55 ms |
| Consultant profile — peer | 300 | 0 | 172,500 | 133.77 ms |
| Consultant profile — parenting team | 200 | 0 | 75,000 | 163.93 ms |
| Agency languages — primary | 200 | 0 | 4,000 | 34.89 ms |
| Agency languages — multi | 200 | 0 | 4,000 | 40.47 ms |
| Liveness control | 100 | 0 | 1,500 | 31.92 ms |
| **Overall** | **1,400** | **0** | **405,400** | **114.28 ms** |

The overall run completed in 1.627 seconds at 860.47 requests/second with
35.69 ms mean latency and 343.82 ms maximum latency. This is a bounded local
mixed-read regression proof, not a production capacity claim.

A reproducible two-replica variant is:

```bash
bash scripts/load/run-seeded-public-read-replicas.sh
```

The runner requires Java 21. It retains an already active Java 21 runtime and
auto-selects an installed JDK 21 through `java_home` on macOS; unsupported
runtimes fail before Docker or any dependency state starts.

It packages the real application jar, starts isolated MariaDB 11.0.6 and Redis
7 containers, starts two distinct UserService JVMs against that shared state,
seeds the exact integration-test dataset, and sends requests directly to both
replicas in deterministic round-robin order. Scheduling and external chat event
listeners are disabled so the result isolates the seeded public-read paths. The
CLI reports and enforces thresholds for the aggregate, each operation, and each
replica.

The runner also exposes the normal Micrometer endpoint only on the disposable
local JVMs and captures `userservice.outbound.http.calls`,
`userservice.outbound.http.latency` and
`userservice.outbound.http.payload` immediately before and after the measured
workload. It fails if the AgencyService path is not exercised, produces more
than one call per consultant-profile read, loses a latency/payload measurement,
or exceeds the configured mean-latency or response-payload bounds. The bounds
can be overridden with
`USERSERVICE_LOAD_MAX_AGENCY_CALLS_PER_CONSULTANT_READ`,
`USERSERVICE_LOAD_MAX_AGENCY_MEAN_LATENCY_MS` and
`USERSERVICE_LOAD_MAX_AGENCY_RESPONSE_BYTES_PER_CALL`.

Cleanup removes both JVMs, the dependency containers, the AgencyService stub,
and the temporary run directory.

Local two-replica proof on 2026-07-27:

| Scope | Requests | Failures | Response bytes | Mean | p95 | Max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Replica one | 700 | 0 | 274,800 | 44.79 ms | 81.29 ms | 183.89 ms |
| Replica two | 700 | 0 | 206,200 | 30.01 ms | 56.67 ms | 218.61 ms |
| **Overall** | **1,400** | **0** | **481,000** | **37.40 ms** | **68.61 ms** | **218.61 ms** |

The current rerun completed in 1.648 seconds at 849.42 requests/second. The
slowest named operation was `consultant-profile-peer` at 82.23 ms p95; all six
operations had zero failures. The measured 900 consultant-profile reads caused
exactly 900 AgencyService calls across both JVMs: 1.0 call per profile read,
6.74 ms mean and 140.55 ms maximum outbound latency, with 260,000 response
bytes in total, 288.89 bytes per call on average and 436 bytes maximum. Every
successful call had a latency and payload measurement.

This proves that the bounded mixed-read scenario can run across two real JVMs
sharing MariaDB and Redis, and that its healthy AgencyService dependency has no
hidden retry or 1+N call amplification. It does **not** yet prove replica safety
for concurrent writes, scheduled jobs, authentication and authorization flows,
Kubernetes service routing, or deployed PreDev behavior. The production
replica maximum must therefore remain one until those paths and their
idempotency/locking contracts are exercised.

The same seeded workload was also run with AgencyService deliberately
unavailable. UserService still returned all 1,400 responses through its local
topic fallback at concurrency 32 (121.19 ms p95, 843.2 requests/second).
However, each failed dependency attempt emitted a WARN stack trace. That proves
fallback continuity while exposing log amplification as a separate operational
risk; it is not equivalent to the healthy-dependency result above.

The earlier control proof on 2026-07-25 used a real started UserService testing process and
500 requests at concurrency 20 against `/actuator/health/liveness`: 0 failures,
0% error rate, 2,996 requests/second, 6.58 ms mean and 17.84 ms p95 (25.98 ms
max). It is retained as a liveness control and is superseded by the seeded mixed
read scenario for application-path evidence. The
aggregate local health group was `DOWN` because the developer RabbitMQ instance
did not accept the testing profile's credentials; liveness and readiness were
both `UP`, and the dedicated Redis contract passed independently.

Local authenticated-write proof refreshed on 2026-07-28 used two real
UserService JVMs,
one disposable MariaDB 11.0.6 database, shared Redis and a locally signed
consultant JWT verified through a disposable JWK endpoint. Eighty concurrent
tutorial-progress PUTs alternated over both replicas, followed by a read from
each replica: 0 failures, 41.76 ms aggregate p95 and exactly one canonical
database row. After restarting one replica and initializing its authenticated
path on the isolated warm-up scope, 12 further writes and both cross-replica
reads completed with 0 failures and 19.22 ms p95. The runner applies the same
zero-error and 1,000 ms p95 bound per operation and per replica. Startup
liveness and authenticated-path initialization are deliberately separated from
the measured state-transition latency.
MariaDB's native upsert protects one versioned scope. A database advisory lock,
whose name hashes the user identifier, serializes only first writes for a user
so concurrent replicas cannot exceed the per-user row cap while creating
different scopes. Existing-scope writes do not acquire that lock. Real MariaDB
contracts prove both the cross-replica same-scope race and the different-scope
row-cap race, including lock release after a rejected write.
It introduces no Rocket.Chat or Jitsi configuration or dependency. This is a
bounded authenticated state-transition and restart proof for one slice, not
deployed PreDev or whole-service multi-replica evidence.
