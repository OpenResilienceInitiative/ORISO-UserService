# Independent review

## Specification

PASS against `7ec09040`: no remaining actionable findings. The first P2 finding was an older rollback task restoring owner power after a newer grant expired. The final implementation captures the original attempt, accounts for whole-second/equal timestamps, correlates successfully retried operation identities and recognizes persisted grant history. Public tests reproduce and prevent the expired-grant failure.

The owner-authorized update remains first. Self-demotion is bounded to equal peer permission rejection and refreshed state. Owner identity/revision, read-only Matrix authorization, application revocation and retained department membership match the requested behavior and accepted ADR002.

Reviewer: independent `coaccess_spec_review` agent. Review is source evidence; actual Dev acceptance remains separate.

## Standards

PASS: no blocking documented breach. The independent reviewer checked the bounded owner-first fallback, same-requester self-demotion, locked rollback restoration, revision/operation/later-grant guards and the public MariaDB/Synapse regressions.

One optional duplication heuristic remains: the existing `MatrixRoomClient.setUserPowerLevel` has the same GET–copy–PUT shape as the new private helpers. Reusing them could shorten the legacy method, but leaving its existing behavior/logging unchanged is reasonable for this repair. This is not a blocking finding.

Reviewer: independent `coaccess_standards_review` agent. Local gates passed; no Dev acceptance or merge is implied.


## PR #1397 public guard coverage follow-up

Both independent reviewers passed the test-only diff against published `4b97720` without new actionable findings. The new matching-operation case prevents date/active-grant checks from masking identity correlation. The public takeover case changes the ownership revision without adding another advice grant. The existing same-timestamp case now explicitly checks the public creation date against the controlled clock.

Temporary production mutations of UUID, ownership revision and equality comparison each made the corresponding public behavior check fail with actual Matrix power 100 instead of −1. The reviewers inspected the test isolation and supplied sensitivity results; they did not independently execute those runs. All mutations were restored before final validation.

Final local proof is 323 related unit tests plus 9 public integration cases (34.974 s), package, Spotless and diff checks passing. Production source and migrations are unchanged from `4b97720`. Actual Dev acceptance remains separate.


## PR #1397 asynchronous task-consumption follow-up

Both independent reviewers passed the fixture-only diff against `01c690a` without actionable findings. A completed failed worker attempt and consumption of its exact task replace elapsed-time assumptions before final permission checks. Database queries coordinate worker progress only; business assertions remain at public HTTP and actual Matrix. A paused worker now times out rather than producing a false pass.

All three guard mutations produced direct assertion failures after task consumption: UUID 1 case / 1 failure, ownership revision 1 case / 1 failure, equal timestamp 2 cases / 1 failure, all with zero errors. All production mutations were restored. Reviewers inspected the supplied mutation receipts and did not independently execute them.

Final local gates passed: 9 public integration cases in 52.851 s, package, Spotless and diff checks. The unchanged production and unit source retain the earlier 323 passing related-unit receipt. Disposable Testcontainers cleanup was verified. No production, migration, merge, deployment or Dev acceptance change is implied.
