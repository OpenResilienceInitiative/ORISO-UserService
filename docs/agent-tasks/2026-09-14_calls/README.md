# Matrix call lifecycle — developer handoff

Fresh local reconstruction on 14 September 2026: 199 targeted tests passed,
zero failures/errors/skips; package and Spotless passed. Independent Standards
and Spec review found no remaining blocking extraction finding. The authoritative
test run was sequential; an earlier overlapping build/test run is not evidence.

Owner: **Shazia (`shazia-k`)**. Local source handoff targeting dev, no deployment.

Replays `586bc459` plus six retained call lookup/retry corrections onto dev
`57570b8ef95fccc1018165cb961564dfb52142f1`; preserves current account recovery.
Includes authorized room/session call bindings, persisted lifecycle/state,
notification deduplication, Matrix RTC membership and retryable auth/transport
lookup handling. See `docs/matrix-call-lifecycle.md` for the protocol.

```sh
# Java 21
./mvnw -B -Dtest=MatrixCallStateControllerTest,MatrixCallNotificationIT,MatrixCallTenantIT,MatrixEventListenerServiceTest,MatrixRtcMembershipEventTest,MatrixSynapseServiceTest test
./mvnw -B package -Dskip.unit-tests=true -Dskip.integration-tests=true
./mvnw -B spotless:check
```

This is separate from call email templates and Case Handover. When combining other
branches, preserve both additions in `EventNotificationService` and migration master.
No actual media session, delivered email, deployed image or browser acceptance is proved
by the local tests. Shazia owns remaining integration and test execution before review.
