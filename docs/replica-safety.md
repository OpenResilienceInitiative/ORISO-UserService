# UserService replica-safety inventory

## Current support boundary

UserService currently supports exactly one application replica. ORISO-Helm#158
enforces that boundary until the Matrix listener, every scheduled side effect,
and the remaining queue coordination have two-instance proof.

This change removes a restart and request-routing dependency from the
authentication flows: magic-link and password-reset tokens are stored in Redis
with atomic claim semantics. Redis unavailability fails closed; the service
does not fall back to heap state.

## State classification

| State | Location | Correctness role | Current decision |
| --- | --- | --- | --- |
| Magic-link tokens | Redis | One-time authentication | Shared, TTL-bound, atomic claim |
| Password-reset tokens | Redis | One-time authentication | Shared, one per account, atomic claim |
| Consultant availability | Redis | Live-chat routing | Shared lease with bounded TTL |
| Matrix sync cursor/admin token | Process heap | Event consumption | Single-replica blocker |
| Matrix room lookup maps | Process heap with DB fallback | Performance | Rebuildable cache |
| Active-view map | Process heap | Notification suppression hint | Best-effort only; scale-out review required |
| Matrix impersonation tokens | Process heap | Performance | Expiring cache; re-login on miss |
| Caffeine agency/tenant/settings data | Process heap | Performance/read freshness | TTL-bounded; invalidation bound not yet proven |
| Scheduled workflows | Each process | External/database side effects | Single-replica blocker unless individually idempotent |

## Redis token guarantees

- A token written by one instance can be claimed by another.
- Claim is an atomic read-and-delete operation.
- Password reset keeps at most one outstanding token per account across
  instances; account identifiers are SHA-256 hashed in Redis keys.
- A transient downstream failure may restore the same claim only for its
  original remaining TTL.
- Restoration cannot replace a newer password-reset token.
- Token values, account identifiers, and raw Redis errors are not logged.
- PR validation starts real Redis and proves the availability lease plus
  cross-instance token contracts as a blocking check.

## Scale-out handoff

UserService#543 owns the complete measurable state inventory,
UserService#379 owns group-chat scheduler locking, and UserService#216 owns the
remaining queue/coordination migration. A future scale-out PR must add Matrix
leader handoff, scheduler locking/idempotency proof, cache freshness bounds,
and a deterministic two-replica E2E run before ORISO-Helm may accept a replica
count above one.
