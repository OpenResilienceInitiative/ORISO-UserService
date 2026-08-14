# UserService Invite-Link API Local Testing

This guide covers local testing for the UserService invite-link APIs with the hybrid setup:
UserService runs locally on Java 21, while Keycloak, TenantService, AgencyService, ConsultingTypeService,
Matrix, and other dependencies can stay on the remote dev environment.

Do not commit real tokens, passwords, or copied development credentials.

## Current Local Service

Use this base URL for the currently running local UserService:

```text
http://localhost:8082
```

If you restart UserService with `SERVER_SERVLET_CONTEXT_PATH=/service`, change the Postman `baseUrl`
variable to:

```text
http://localhost:8082/service
```

The current process was verified on root paths, so the examples below use `http://localhost:8082`.

## Prerequisites

- UserService is running locally with JDK 21.
- Remote dev database and remote dev services are reachable.
- You have a valid Keycloak bearer token for an admin user.
- The admin token must include at least one of these authorities:
  - `AUTHORIZATION_TENANT_ADMIN`
  - `AUTHORIZATION_USER_ADMIN`
  - `AUTHORIZATION_RESTRICTED_AGENCY_ADMIN`
- You know valid test IDs for the same tenant:
  - `tenantId`
  - `agencyId`
  - `topicId`
  - optional `consultantId` for counsellor invite links

For Postman, import:

```text
documentation/postman/ORISO-UserService-InviteLinks.postman_collection.json
documentation/postman/ORISO-UserService-Local.postman_environment.json
```

Then select the `ORISO UserService Local` environment and set:

```text
username=<your Keycloak username>
password=<your Keycloak password>
tenantId=<tenant id>
agencyId=<agency id>
topicId=<topic id>
consultantId=<consultant id if testing COUNSELLOR links>
```

Then run:

```text
Authentication / Get Admin Token - Password Grant
Authentication / Verify Admin Token Against Local List Endpoint
```

The token request stores the returned access token in `adminToken`. Do not include `Bearer` in the
environment value; the collection adds that automatically.

The local run script points UserService at:

```text
https://auth.oriso-dev.site
```

So the token must come from the same Keycloak realm. A token from a different live/prod Keycloak
will normally fail with `401 Unauthorized` unless UserService is restarted with matching auth config.

## Authentication And Headers

Create and list endpoints require a bearer token:

```http
Authorization: Bearer <adminToken>
```

For tenant-scoped testing, also send:

```http
X-Tenant-Id: <tenantId>
```

For state-changing admin requests, the Postman collection also sends CSRF-safe local headers:

```http
X-CSRF-Token: local-postman-csrf
X-CSRF-Bypass: local-postman-csrf
Cookie: CSRF-TOKEN=local-postman-csrf
```

The redeem endpoint is public and does not require a bearer token.

## Endpoint Summary

| Purpose | Method | Path | Auth |
| --- | --- | --- | --- |
| Create invite link | `POST` | `/useradmin/invitelinks` | Admin bearer token |
| List invite links | `GET` | `/useradmin/invitelinks` | Admin bearer token |
| Redeem invite link | `POST` | `/users/invitelinks/{token}/redeem` | Public |

## Enum Values

`linkKind`:

```text
TENANT
COUNSELLOR
EXTERNAL_INBOUND
```

`chatType`:

```text
LIVE_CHAT
```

`anonymity`:

```text
FULL
```

`status` filter:

```text
ACTIVE
USED
EXPIRED
```

Defaults applied by the service when fields are omitted:

```text
linkKind = EXTERNAL_INBOUND
chatType = LIVE_CHAT
anonymity = FULL
```

## 1. List Invite Links

In Postman, use the dedicated folder:

```text
Listing Invite Links
```

It contains:

```text
List All Links - Flat Array
List Links - Paged
List Links - Active Only
List Links - By Kind
List Links - By Topic And Chat Type
List Links - Confirm Saved Invite Token
```

Request:

```bash
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks"
```

Expected result:

- `200 OK`
- Without `page` or `size`, response is a plain JSON array.

Example shape:

```json
[
  {
    "id": 123,
    "token": "generated-token",
    "tenantId": 1,
    "agencyId": 1,
    "topicId": 1,
    "topicName": "Example topic",
    "linkKind": "EXTERNAL_INBOUND",
    "chatType": "LIVE_CHAT",
    "anonymity": "FULL",
    "consultantId": null,
    "notes": "Local Postman test",
    "createdByUserId": "admin-user-id",
    "createdByUsername": "admin-user",
    "createDate": "2026-07-01T10:00:00",
    "expiresAt": "2026-07-08T10:00:00",
    "usedAt": null,
    "usedBySessionId": null,
    "status": "ACTIVE"
  }
]
```

Paged request:

```bash
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks?page=0&size=20&status=ACTIVE"
```

Expected paged shape:

```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "page": 0,
  "size": 20
}
```

Supported filters:

```text
linkKind
topicId
chatType
status
page
size
```

`size` is clamped to a maximum of `100`.

Common listing examples:

```bash
# All links as flat array
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks"

# Paged list
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks?page=0&size=20"

# Active only
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks?page=0&size=50&status=ACTIVE"

# By kind
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks?page=0&size=50&linkKind=EXTERNAL_INBOUND"

# By topic and chat type
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  "http://localhost:8082/useradmin/invitelinks?page=0&size=50&topicId=1&chatType=LIVE_CHAT"
```

There is no single-link lookup endpoint by token in this controller. To confirm a created token
appears in the list, use the Postman request:

```text
Listing Invite Links / List Links - Confirm Saved Invite Token
```

## 2. Create External Inbound Invite Link

Request:

```bash
curl -i -X POST "http://localhost:8082/useradmin/invitelinks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-Token: local-postman-csrf" \
  -H "X-CSRF-Bypass: local-postman-csrf" \
  -H "Cookie: CSRF-TOKEN=local-postman-csrf" \
  -d '{
    "agencyId": 1,
    "topicId": 1,
    "linkKind": "EXTERNAL_INBOUND",
    "chatType": "LIVE_CHAT",
    "anonymity": "FULL",
    "notes": "Local invite-link API test",
    "expiresInDays": 7
  }'
```

Expected result:

- `201 Created`
- Response contains a generated `token`.
- The Postman collection automatically saves this `token` into the `inviteToken` environment variable.

Important validations:

- `notes` cannot exceed 500 characters.
- `expiresInDays` must be between `1` and `365`.
- `expiresInDays` may be omitted or `null` for no expiry.
- `agencyId`, if provided, must belong to the caller tenant.
- `topicId`, if provided, must exist.

## 3. Create Invite Link With Defaults

This is useful to verify service defaults.

Request body:

```json
{
  "agencyId": 1,
  "topicId": 1,
  "notes": "Default field test",
  "expiresInDays": 7
}
```

Expected default fields in response:

```json
{
  "linkKind": "EXTERNAL_INBOUND",
  "chatType": "LIVE_CHAT",
  "anonymity": "FULL",
  "status": "ACTIVE"
}
```

## 4. Create Counsellor Invite Link

Use this only when you have a valid `consultantId` in the same tenant.

Request body:

```json
{
  "agencyId": 1,
  "topicId": 1,
  "linkKind": "COUNSELLOR",
  "chatType": "LIVE_CHAT",
  "anonymity": "FULL",
  "consultantId": "valid-consultant-id",
  "notes": "Counsellor-routed invite-link test",
  "expiresInDays": 7
}
```

Expected result:

- `201 Created`
- Response contains the same `consultantId`.

Expected negative case:

- If `linkKind` is `COUNSELLOR` and `consultantId` is missing, the service returns `400 Bad Request`.

## 5. Redeem Invite Link

Use the `token` returned by a create request.

Request:

```bash
curl -i -X POST \
  "http://localhost:8082/users/invitelinks/$INVITE_TOKEN/redeem"
```

Expected result:

- `200 OK`
- Response includes registration routing metadata.

Example shape:

```json
{
  "userName": null,
  "sessionId": null,
  "accessToken": null,
  "expiresIn": null,
  "refreshToken": null,
  "refreshExpiresIn": null,
  "rcUserId": null,
  "rcToken": null,
  "rcGroupId": null,
  "tenantId": 1,
  "agencyId": 1,
  "consultingTypeId": 1,
  "topicId": 1
}
```

Current branch behavior:

- Redeem returns metadata for frontend registration.
- It does not create a Keycloak, Rocket.Chat, or Matrix user in this service path.
- It does not mark the link as `USED`; links remain reusable until expiry unless stored status is changed elsewhere.

## Negative Tests

### Missing Admin Token

```bash
curl -i "http://localhost:8082/useradmin/invitelinks"
```

Expected:

```text
401 Unauthorized
```

### Invalid Enum

```bash
curl -i "http://localhost:8082/useradmin/invitelinks?status=INVALID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: 1"
```

Expected:

```text
400 Bad Request
```

### Unknown Redeem Token

```bash
curl -i -X POST \
  "http://localhost:8082/users/invitelinks/not-a-real-token/redeem"
```

Expected:

```text
404 Not Found
```

This was verified against the currently running local service.

### Expired Invite Link

Create a short-lived link, wait until `expiresAt` is in the past, then redeem.

Expected:

```text
400 Bad Request
```

Message:

```text
Invite link expired
```

## Troubleshooting

### `401 Unauthorized`

The request is missing a bearer token, the token expired, or the token is not accepted by the local
resource server configuration.

Fix:

- Refresh `adminToken`.
- Ensure the local service points to the remote dev Keycloak issuer/JWK config.

### `403 Forbidden`

The token is valid but does not include one of the required admin authorities, or the selected
agency/consultant is outside the caller tenant.

Fix:

- Use a tenant/user/restricted-agency admin token.
- Check `X-Tenant-Id`, `agencyId`, and `consultantId`.

### `404 Not Found` On Redeem

The token does not exist in the UserService database currently used by the local service.

Fix:

- Create the invite link against the same local UserService first.
- Use the exact `token` returned by create.

### `/service/...` Paths Return `401` Or Do Not Match

The current local process was verified on root paths:

```text
http://localhost:8082/useradmin/invitelinks
http://localhost:8082/users/invitelinks/{token}/redeem
```

If UserService is restarted with `SERVER_SERVLET_CONTEXT_PATH=/service`, use:

```text
http://localhost:8082/service/useradmin/invitelinks
http://localhost:8082/service/users/invitelinks/{token}/redeem
```

In Postman, change only the `baseUrl` variable.

### Full Health Endpoint Is Slow Or Down

Full actuator health may wait on optional dependencies. For invite-link API testing, verify the API
route directly:

```bash
curl -i -X POST "http://localhost:8082/users/invitelinks/not-a-real-token/redeem"
```

Expected route confirmation:

```text
404 Not Found
```
