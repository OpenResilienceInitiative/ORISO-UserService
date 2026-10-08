# Agency Matrix identity-only transport (#289)

## Contract

`GET /internal/agencies/{agencyId}/matrix-service-account` and the provisioning
`POST` expose only `matrixUserId`. Authentication, technical-user authority,
agency/tenant headers and room-access checks remain unchanged.

UserService's six agency-account callers impersonate that full Matrix identity
through the existing Synapse admin API. `loginAsUserAccessToken` requests a
ten-minute expiry. The returned token acts as the ordinary agency account, not
the admin, and cannot grant access to rooms the agency does not already belong to.
There is no agency-password fallback. The normal admin/human login implementation
is not removed.

## Rollout

1. Deploy this UserService change first. Its identity-only DTO deliberately
   ignores the older AgencyService response's additional password field; it does
   not store, reuse or reserialize it. Verify holding rooms, enquiry acceptance,
   late joins/revocations, message reads/system notifications and team discussion
   create/archive on the target environment.
2. Deploy AgencyService's identity-only response change. Repeat the same checks
   and confirm both GET and POST responses have no password field.
3. Only then consider the transport-removal implementation complete. Do not
   auto-close the cross-service issue with the first PR.

No new admin privilege or configuration is required: these flows use the same
admin impersonation path already used for counsellors and advice seekers. A
working existing Synapse admin login is a rollout prerequisite. If admin token
creation is unavailable, existing best-effort/room-creation failure behavior
applies; the service does not silently fall back to a reusable agency password.
An unassigned-enquiry message read returns `502` with a generic reason when the
agency identity or impersonation token is unavailable, not a successful empty
conversation (the API error contract).

## Rollback and remaining decisions

Restore the old AgencyService response **before** reverting UserService. Older
UserService callers require that response's password field. Such a rollback
deliberately restores password transport; investigate the impersonation failure
instead of making this a permanent fallback.

This change neither drops encrypted database password fields nor rotates existing
accounts. Provisioning still writes encrypted account credentials. Stored-secret
retirement/rotation, old device/token cleanup, release placement and deployment
approval remain separate operator/product decisions.

## Local verification

`AgencyPasswordFreeSynapseIT` runs all six production callers against disposable
Synapse v1.158.0 with synthetic accounts that have **no password**. Matrix HTTP,
identity, membership, power levels, messages, archive denial and bounded-token
expiry/403 checks are real. Domain repositories, authenticated ORISO context and
the AgencyService identity client are test doubles. AgencyService has separate
response/security contract tests. This is not full-stack browser or Dev proof.

With JDK 21 and Docker:

```sh
./mvnw -B test-compile surefire:test@integration-tests \
  -Dtest=AgencyPasswordFreeSynapseIT -Djacoco.skip=true
```

Synapse API reference:
https://element-hq.github.io/synapse/latest/admin_api/user_admin_api.html#login-as-a-user
