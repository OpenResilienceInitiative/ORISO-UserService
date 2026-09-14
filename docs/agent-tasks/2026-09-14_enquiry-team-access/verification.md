# Enquiry team access — local verification

Refs https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/1149.

## Behavior

A team room is reported usable only after the caller joins. Failed joins produce a retryable HTTP 502; room ownership persists. Concurrent room creation returns the already committed winner and removes only the current request's unused loser. Participant registration tolerates a confirmed concurrent insertion for the same caller.

Database writes and conflict reads use separate transactions; external Matrix calls are outside those writer transactions. Existing agency eligibility and archive behavior remain the authority.

## Evidence

- Controller regressions failed before fixes for false-positive join success, concurrent creation, failed cleanup reporting and concurrent participant insertion.
- Actual MockMvc controller/advice test observes HTTP 502 for a failed join, then 200 with the existing room on retry.
- H2 integration exercises concurrent opening through controller responses and the external Matrix boundary. It checks one shared surviving room and the expected memberships.
- Final related suite: 83 tests passed, including H2 parallel opening by different people and by the same person. Final package and formatting gates passed. An earlier full unit run passed 4,380 tests before the participant-race change; that historical total is not a full-suite claim for the final source.

Reproduce with Java 21:

```sh
./mvnw -Dtest='TeamDiscussion*Test,TeamDiscussion*IT,AgencyLateJoinerMembershipServiceTest,AgencyMembershipSyncListenerTest' test
./mvnw -B package -Dskip.unit-tests=true
./mvnw -B spotless:check
```

## Outstanding acceptance and recovery limits

- Agency removal must revoke retained Matrix team-room membership; the additional public membership-change test seam is awaiting user confirmation.
- Encrypted history for late joiners and real two-colleague Dev acceptance are unverified.
- An interruption after Matrix creation but before the database commit can leave an empty untracked room. A failed purge is surfaced as HTTP 502 and logged with the unused room ID, but automated durable recovery is not implemented.
- Other database failures and uncertain commit outcomes require recovery design; never purge a room whose committed ownership is uncertain.

This is a partial implementation of US1149. Do not close the issue or claim complete team-access acceptance on these checks alone.
