# UserService #1396: read-only advice access after a takeover

A former counsellor with Matrix power 100 could not request advice access while the current owner also had power 100. Matrix rejected the owner's demotion of their equal-powered colleague, so the public request returned HTTP 500.

The confirmed test boundary is the authenticated public request, consent decision and status API, together with actual Matrix read/send authorization. Frank confirmed this boundary on 14 October 2026. Tests use disposable MariaDB, Redis and Synapse containers and synthetic identities; JWT signature verification is substituted. They do not establish Dev browser acceptance.

## Policy and source

- Source baseline: `7ec09040f83959de6c48af9ef359efae6f929623`.
- Accepted ADR: `ORISO-Docs/oriso-platform/decisions/ADR-002-silent-room-membership-and-access-control-curtain.md`. Ownership is exclusive; ordinary department membership remains. Objection ends application access, rather than providing cryptographic eviction.
- Current online graph metadata and knowledge-graph URLs under `https://understand.oriso.org/ua/current/ORISO-UserService/` returned HTTP 404. The fresh source and accepted ADR were inspected directly.
- The final fixture uses Synapse 1.158.0 and room version 11, matching the versions verified on Dev for case 251. Container digest equivalence is not claimed. The first historical RED used room version 7.

## Red–green evidence

| Boundary | RED | GREEN |
| --- | --- | --- |
| Former owner requests read-only advice access | Baseline room 11: ordinary member control passed, former owner returned 500 instead of 201; 2 cases, 1 failure, 18.49 s. The original room 7 RED also reproduced the equal-power rejection. | Public request returns 201; existing advice history is readable; Matrix refuses sends with 403; the current owner remains responsible; objection returns 200 and closes the application grant. |
| Database rejects the grant after Matrix demotion | Original power remained −1 instead of 100 after rollback; 2 cases, 1 failure, 18.55 s. | Original power is restored. A temporary Matrix outage is retried by the real scheduled repair worker without a user retry. |
| Older rollback task meets a later, expired grant | At the same whole-second timestamp, the old worker restored 100 instead of preserving the later grant's −1 pending normal expiry cleanup; the expired case failed. | Active and expired later grants both supersede the old task. Equal timestamps fail safely; a matching operation identity also protects a successfully retried grant. |

The public suite also verifies that both owner and requester power updates being denied leaves no application grant. Both message aliases reject the former counsellor's reads/writes after objection; requests include normal matching CSRF cookie/header values and the current owner's sends succeed as a positive control.

Final candidate checks passed on Java 21:

| Check | Result |
| --- | --- |
| Public HTTP/MariaDB/Synapse/Redis integration | 9 cases, 0 failures, 0 errors, 0 skipped; 52.851 s (latest task-consumption follow-up) |
| Related unit tests | 323 cases, 0 failures, 0 errors, 0 skipped: handover 213, repair 8, room client 36, Synapse adapter 66 |
| Maven package | Passed; `target/UserService.jar` produced |
| Spotless and `git diff --check` | Passed |
| Independent source specification review | Passed after correcting the expired superseding-grant finding |
| Independent standards review | Passed; one nonblocking legacy helper-duplication heuristic retained |

The public integration applies the complete Liquibase changelog to a fresh MariaDB and validates the resulting JPA schema. Durable queue tests persist and reload the new nullable payload fields. The normal Maven generation phase reports pre-existing nonfatal `$ref` schema warnings; those are separate from the passing compiler/test/package gates.

## Scope and delivery

The owner-authorized power update remains first. Self-demotion is restricted to a real 403/M_FORBIDDEN rejection with equal captured and refreshed peer power, using the requester's own existing short-lived identity. No administrator token bypass is used. Failed demotion remains a failed grant.

Rollback restoration holds the session lock and checks the ownership revision, persisted superseding grants, original attempt boundary, operation identity and expected Matrix power. Failed restoration extends the existing durable repair queue. Three nullable columns carry the prior power, ownership revision and attempt operation identity; no live database is changed by this work.

Ordinary objection and expiry continue restoring member power 0, rather than former-owner power 100. Review, merge, image build, deployment and full browser acceptance remain separate delivery gates.


## PR #1397 review follow-up

CodeRabbit comment `4267896111` identified two useful coverage gaps: a matching-operation retry and a changed ownership revision were not separately isolated. Its claim that the existing equal timestamps depended on execution timing was incorrect: the primary fixture clock remains frozen across both requests. That case now also checks its public `createdAt` response against the given whole-second fixture time.

The follow-up adds public behavior checks rather than private or mocked repair-helper tests:

| Guard | Independent public scenario | Sensitivity result |
| --- | --- | --- |
| Matching operation identity | A failed grant is retried with the same UUID after moving the fixture clock four hours backward. Returning to the original time expires the retry, so date/active-grant checks cannot mask the UUID safeguard. | Removing only the UUID safeguard causes 1 case to fail: Matrix power becomes 100 instead of −1. |
| Ownership revision | A third eligible counsellor takes over through the normal public request, advancing revision 0→1 while the first failed advice request has no grant. | Removing only the revision safeguard causes 1 case to fail: the old worker restores Matrix power 100 instead of preserving −1. |
| Equal persisted timestamp | The later advice grant has the same whole-second fixture time as the failed attempt, a different UUID and an expired status. | Replacing equality-safe comparison with strict-after causes the expired scenario to fail (2 cases, 1 error): Matrix power becomes 100 instead of −1. |

These are guard-sensitivity checks, not additional original defect reproductions. Every temporary mutation was reverted byte-for-byte. The follow-up changes no production source or migration: `git diff 4b97720 --name-only -- src/main` returned no paths.

Final follow-up gates: 323 related unit tests and 9 public integration cases passed; Maven package, Spotless and diff checks passed. Independent specification and standards follow-up reviews passed. After the test JVM exited, `docker ps -a --filter label=org.testcontainers=true` returned no disposable containers. Existing local containers were not changed.


## PR #1397 asynchronous repair completion follow-up

CodeRabbit comment `4268100880` correctly identified that a guarded repair may make no Matrix write. The previous elapsed-time window could therefore observe unchanged power before the worker had consumed the task. The fixture now captures the opaque repair ID only after a failed scheduled attempt has completed, then waits for that exact task to be consumed before asserting Matrix power and send authorization. Database reads coordinate the disposable worker only; ownership and permission outcomes still use public HTTP and actual Matrix.

With this synchronization, removing only the UUID guard yields 1 case / 1 assertion failure / 0 errors; removing only the ownership-revision guard yields the same result. Changing the equality-safe timestamp comparison to strict-after yields 2 cases / 1 assertion failure / 0 errors. Each failure observes actual power 100 instead of expected −1 after task consumption. Production mutations were restored byte-for-byte before final validation.

The final public run passed all 9 cases in 52.851 s, with no failures, errors or skips. Maven package, Spotless and diff checks passed. The previously verified 323 related unit cases remain valid because this follow-up changes no production or unit source. Both independent follow-up reviews passed; reviewers inspected source and supplied results rather than rerunning the suite. The Testcontainers inventory was empty after the test JVM exited. Production and migrations remain unchanged from `01c690a` and `4b97720`; Dev acceptance remains pending separately.
