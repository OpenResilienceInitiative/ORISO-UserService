# Group creation policy boundary

This is the runtime consumer repair for UserService issue 1171 under Admin771. The
existing group feature gate uses fresh public TenantService policy data and the
AgencyService's existing effective format booleans. It does not introduce a
second policy owner, a global tenant-settings merge, or an agency policy-map
schema dependency.

## Decision

The exact `featureInternalGroupChatEnabled` or `featureSelfHelpGroupsEnabled`
canonical policy decides the requested format. An absent format key falls back
to the canonical `featureGroupChatV2Enabled` policy for transitional providers.
An explicit modern key is independent of the old V2 master.

| Tenant authority | Agency effective format | Result |
| --- | --- | --- |
| ENFORCED off | Any dormant agency value | Refuse before creation |
| ENFORCED on | Any dormant agency value | Permit at the feature gate |
| SUGGESTED on/off | Present effective format | Agency value decides |
| SUGGESTED on/off | No usable agency settings or availability failure | Tenant recommendation decides |
| No selected canonical policy | Legacy flat tenant V2 off | Refuse, retaining the old hard cap |
| No selected canonical policy | Legacy tenant on | Preserve the old agency V2/format checks |

An absent agency format uses the selected tenant recommendation when an exact
modern canonical policy exists. Only the legacy/canonical-V2 transition path may
fall back to the agency V2 boolean. A modern policy is neither granted nor vetoed
again by a separate legacy V2 check. This is a
feature decision; it does not replace actor authorization, agency access, DPA,
participant validation, or the remaining creation transaction.

## Sources and availability

The bundled public Tenant client gains only `RestrictedTenantDTO.permissionPolicies`
and its `BooleanPermissionPolicy` / `PermissionPolicyMode` definitions, copied
from TenantService provider revision `21f65d4a316ad81fd51da105c7a5d5f0a01a020d`.
The map is read-only. Suggestions are deliberately not inferred from flattened
legacy settings.

There is one uncached tenant lookup and at most one agency lookup per gate call.
The concrete tenant response must match the consultant's tenant (or the configured
single tenant). The requested agency ID must match a unique returned projection;
a present projection tenant must match the established tenant. The agency
contract's optional missing tenant ID stays compatible with older projections.

User1171 explicitly retains availability fallback: an unknown agency, 404,
missing settings, timeout or service failure uses the tenant decision and logs
transport failure. An authorization refusal, foreign/ambiguous projection, or
incomplete selected canonical policy is not an availability fallback. These
conditions fail closed. HTTP 200 JSON decoding/binding failures and unsupported
content types produce a sanitized 503; they never use a permissive tenant
fallback. Availability logs name only the exception class, without upstream
response content or URLs. A TenantService failure cannot be replaced by an agency
value.

## Verification boundary

Regression coverage includes both formats, all policy directions, independent
modern split flags, canonical V2 transition fallback, unchanged legacy behavior,
agency outage/missing settings, and projection identity. The real controller
delegate is also exercised with generated Tenant/Agency clients and the normal
Boot HTTP converters; refusal must occur before the create facade is called.

Both supported creation routes (`/users/chat/new` and `/users/chat/v2/new`) use
the same gate before the create facade. Frontend still selects V1 when its legacy
V2 routing flag is false, so V1 must not bypass modern format policy. The existing
controller role checks and creation fixtures remain in place.
Local tests and package checks are not integrated Dev acceptance. The deployed
AgencyService must serve the correct effective format values for the repaired
suggestion cascade to be accepted end to end.
