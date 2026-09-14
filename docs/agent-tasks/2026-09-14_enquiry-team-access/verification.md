# Enquiry team access — local verification

Refs https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/1149.

## Behavior

A team room is reported usable only after the caller joins. Failed joins produce a retryable HTTP 502; room ownership persists. Missing local Matrix identity or agency credentials remain internal configuration errors (HTTP 500). Concurrent room creation returns the already committed winner and removes only the current request's unused loser. If that purge fails, the losing room is stored durably and retried by the leased cleanup scheduler; the retry refuses to purge any room referenced by a persisted discussion. Participant registration tolerates a confirmed concurrent insertion for the same caller.

When a consultant leaves an agency, the existing after-commit membership listener now removes them from both the agency's open enquiry rooms and the open team-discussion rooms they actually joined. The database query is restricted by consultant, agency and open discussion status; rooms belonging to another agency are not selected.

Database writes and conflict reads use separate transactions; external Matrix calls are outside those writer transactions. Existing agency eligibility and archive behavior remain the authority.

## Evidence

- Controller regressions failed before fixes for false-positive join success, concurrent creation, failed cleanup reporting and concurrent participant insertion.
- Actual MockMvc controller/advice test observes HTTP 502 for a failed join, then 200 with the existing room on retry.
- H2 integration exercises concurrent opening through controller responses and the external Matrix boundary. It checks one shared surviving room and the expected memberships.
- Cleanup-task tests cover durable recording, retained failed retries, successful deletion, Matrix exceptions and the guard against purging a persisted winner. The migration contract proves the append-only `0100` table is included by the master changelog.
- Agency-membership tests cover revocation of a joined team-discussion room through the same after-commit removal path as the main enquiry rooms.
- Final related suite: 93 tests passed, including H2 parallel opening by different people and by the same person. The final full unit suite passed all 4,470 tests; package and formatting gates passed as well.

Reproduce with Java 21:

```sh
./mvnw -Dtest='TeamDiscussion*Test,TeamDiscussion*IT,AgencyLateJoinerMembershipServiceTest,AgencyMembershipSyncListenerTest' test
./mvnw -B package -Dskip.unit-tests=true
./mvnw -B spotless:check
```

## Outstanding acceptance and recovery limits

- Encrypted history for late joiners and real two-colleague Dev acceptance are unverified.
- An interruption after Matrix creation but before the database commit can still leave an empty untracked room. The new durable task covers a confirmed losing room whose immediate purge failed; it does not claim crash recovery before that failure is observed.
- Other database failures and uncertain commit outcomes require recovery design; never purge a room whose committed ownership is uncertain.

This is a partial implementation of US1149. Do not close the issue or claim complete team-access acceptance on these checks alone.


## API authorization coverage follow-up

The actual SecurityConfig filter chain, TeamDiscussionController and TeamDiscussionFacade are composed in an isolated test web context. GET permits an eligible colleague (204 when no discussion exists), rejects an asker (403), and rejects a foreign-agency colleague (403). POST supplies matching CSRF cookie/header values: the asker remains forbidden while the eligible colleague opens the room (200 plus room ID). Authentication is supplied through Spring Security test support; this does not prove JWT decoding or live Keycloak authentication. Repository/Matrix fixtures remain local. No productive authorization change was necessary; this is coverage of existing behavior, not a claimed red-green product repair. The nested fixture has no component stereotype and is explicitly registered only by this test. AppConfig uses a broad component scan that otherwise discovers even TestConfiguration classes. A mixed facade/ActuatorControllerIT run reproduced the IdentityConfig binding failure before removal of that stereotype and passed afterward (21 tests).
