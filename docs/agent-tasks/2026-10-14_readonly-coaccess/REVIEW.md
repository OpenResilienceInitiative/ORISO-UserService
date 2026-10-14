# Independent review

## Specification

PASS against `7ec09040`: no remaining actionable findings. The first P2 finding was an older rollback task restoring owner power after a newer grant expired. The final implementation captures the original attempt, accounts for whole-second/equal timestamps, correlates successfully retried operation identities and recognizes persisted grant history. Public tests reproduce and prevent the expired-grant failure.

The owner-authorized update remains first. Self-demotion is bounded to equal peer permission rejection and refreshed state. Owner identity/revision, read-only Matrix authorization, application revocation and retained department membership match the requested behavior and accepted ADR002.

Reviewer: independent `coaccess_spec_review` agent. Review is source evidence; actual Dev acceptance remains separate.

## Standards

PASS: no blocking documented breach. The independent reviewer checked the bounded owner-first fallback, same-requester self-demotion, locked rollback restoration, revision/operation/later-grant guards and the public MariaDB/Synapse regressions.

One optional duplication heuristic remains: the existing `MatrixRoomClient.setUserPowerLevel` has the same GET–copy–PUT shape as the new private helpers. Reusing them could shorten the legacy method, but leaving its existing behavior/logging unchanged is reasonable for this repair. This is not a blocking finding.

Reviewer: independent `coaccess_standards_review` agent. Local gates passed; no Dev acceptance or merge is implied.
