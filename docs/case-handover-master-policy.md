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
records the first failed refresh; a successful response clears it. The existing
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
