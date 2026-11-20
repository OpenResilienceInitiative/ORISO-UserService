# Canonical Case Handover master

TenantService owns the master and individual reason preferences (UserService #201).
UserService stores effective snapshots, not another configuration source.

Every normal reason-picker read and action that creates or extends access reads one
current TenantService response. The resolved `policies.caseHandoverEnabled` value
and `caseHandoverPolicies.reasons` come from that same response. Both ENFORCED and
SUGGESTED values are resolved by TenantService before this boundary. UserService
does not add agency overrides or substitute a global/default permission.

With the master off, available reasons are empty and a new request, offer,
recipient acceptance, pending client approval or co-access extension is refused
with 403 before grant effects. Configured reason values are preserved. Admin
configuration reads expose those raw resolved preferences even while the master
is off, so off/on restores the previous mixed selection. Existing grants remain
readable; decline, revocation, expiry and reclaim cleanup stay available.

## Freshness and outages

The cache JSON now retains the full tenant policy response, including its tenant
ID and canonical master. Normal runtime reads always try TenantService, regardless
of the scheduled refresh lease, so the next action observes a successful master
change. In single-tenant mode, the reason picker uses the authenticated consultant's
stored tenant when there is no request tenant context. An unknown/nonpositive
stored tenant still yields 503; this matches the existing mutation boundary and
never chooses a global permission or another tenant. Grant notification formatting reuses the authorized reason and makes no
second policy request.

A transport/provider outage may use only a previously validated full snapshot for
the same tenant, preserving both master-on and master-off values. `staleSince`
records the first failed refresh; a successful response clears it. During a runtime
outage, a persistence failure while marking an already validated snapshot stale is
logged by exception class only and does not discard that snapshot. This metadata
write is best effort; failure to read or validate the snapshot still yields 503. The existing
last-known-good availability contract has **no maximum stale age**. The scheduled
five-minute refresh interval is not an expiry bound. Consequently a setting
changed at TenantService during an outage cannot be observed until contact is
restored; no new permissive default is introduced during that interval.

No usable snapshot, a legacy reasons-only row, a mismatched tenant, or a malformed
live authority yields 503 for runtime actions. This includes HTTP 200 invalid JSON,
unknown policy enums and incompatible response content types; a typed sanitized
client failure distinguishes these from token/transport outages before fallback. Legacy reason rows remain readable
for the Admin migration/configuration bridge, but cannot prove runtime permission.
A successful refresh replaces them with a full snapshot. There is no database
schema change, and no reason preference is rewritten on a master toggle.

## Runtime transaction boundary

The public request, offer, recipient/client decision and co-access extension entries
suspend an ambient transaction (`NOT_SUPPORTED`). Their production callers are
nontransactional controller routes; batch requests commit each operation separately.
Future callers must not expect these operations to join an outer atomic transaction.

A short read-only preflight applies the existing access/input guards without taking
session or grant locks. The one fresh policy request runs after that transaction ends.
A separate short mutation transaction locks and reloads the current session/request,
repeats the same guards, and compares tenant, owner, ownership revision and relevant
request/grant state before any effect. A changed ownership or pending grant must be
retried. An unrelated session row-version change alone does not invalidate access.
The reason and its translated copy are resolved against the locked fresh session
using the one captured policy response. Terminal replays and decline/opt-out cleanup
need no policy request. Offer notifications still run only after the mutation commits;
a failed mutation commit rolls back its rows and never sends that notification.

Legacy sessions with a null tenant may use a positive request tenant context only
when the persisted asker's tenant corroborates it. Explicit zero/negative session
tenants and uncorroborated contexts fail closed. Existing actor/domain access checks
still apply in both transactions.

A validated and serialized live response remains authoritative for the current action
when repository persistence fails, including a concurrent first cache insert. Only
repository/transaction persistence failures at that write boundary are tolerated;
malformed authority, serialization failures and unexpected programming errors remain
visible. Runtime reads consult the database only after a provider outage. Admin
write-through remains strict. A failed runtime cache write does not advance durable
last-known-good state: a later outage can use only the last successfully persisted,
validated same-tenant snapshot, subject to the unbounded stale contract above. The
failure is logged by exception class without provider or database details.
