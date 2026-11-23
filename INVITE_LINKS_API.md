# Invite Links API — for the Frontend Developer

> Service: `ORISO-UserService`
> Live Chat links route by topic. Registered invitations can target an agency.
> Runtime contract: `AgencyInviteLinkController.RedeemResponseDTO` on `dev`.

This document is the **contract**. You can build the UI against it without reading any backend
code. If something here disagrees with what the backend does, that's a backend bug — file an
issue, do not work around it in the frontend.

---

## Base URL

| Environment | URL |
|---|---|
| Local dev (laptop) | `http://localhost:8080` |
| Dev cluster        | `https://api.oriso.org` |

All authenticated endpoints expect:

- `Authorization: Bearer <keycloak access token>`
- `X-Tenant-Id: <numeric tenant id>` (required for tenant-scoped admins; super-admin uses it to impersonate a tenant)
- `Content-Type: application/json`

The redeem endpoint (§3) requires **none** of these.

---

## 1. Create invite link

`POST /useradmin/invitelinks`

**Roles allowed:** `AUTHORIZATION_TENANT_ADMIN`, `AUTHORIZATION_USER_ADMIN`, `AUTHORIZATION_RESTRICTED_AGENCY_ADMIN` within their normal scope. External live-chat links (`EXTERNAL_INBOUND` with `LIVE_CHAT`) can only be created, paused, reactivated or deleted by platform admins. They select the target tenant via `X-Tenant-Id`. Omitted link kind and chat type default to this external live-chat combination.

**Request body:**

```json
{
  "topicId": 17,
  "linkKind": "EXTERNAL_INBOUND",
  "chatType": "LIVE_CHAT",
  "anonymity": "FULL",
  "consultantId": null,
  "notes": "Campaign Q3 — landing page",
  "expiresInDays": 30
}
```

| Field | Required | Allowed values | Notes |
|---|---|---|---|
| `topicId` | yes | any topic id in the caller's tenant | 404 if not found |
| `linkKind` | yes | `TENANT` / `COUNSELLOR` / `EXTERNAL_INBOUND` | drives which tab the link appears in |
| `chatType` | yes | `LIVE_CHAT` | only value for now; reserved for future |
| `anonymity` | yes | `FULL` | only value for now; reserved for future |
| `consultantId` | required iff `linkKind = COUNSELLOR` | UUID of a consultant in the caller's tenant | 404 if not found, 403 if outside tenant |
| `notes` | no | free text, ≤ 500 chars | 400 if longer |
| `expiresInDays` | no | integer 1–365, or omit/null for never | 400 if outside range |

**Response `201 Created`:**

```json
{
  "id": 4231,
  "token": "abc123_url-safe_base64",
  "tenantId": 12,
  "topicId": 17,
  "linkKind": "EXTERNAL_INBOUND",
  "chatType": "LIVE_CHAT",
  "anonymity": "FULL",
  "consultantId": null,
  "notes": "Campaign Q3 — landing page",
  "createdByUserId": "uuid",
  "createdByUsername": "PlatformAdminX43",
  "createDate": "2026-05-25T10:40:01",
  "expiresAt": "2026-06-24T10:40:01",
  "usedAt": null,
  "usedBySessionId": null,
  "status": "ACTIVE"
}
```

**Errors:**

| Code | Reason |
|---|---|
| 400 | missing required field, bad enum, range violation |
| 401 | missing/invalid Keycloak token |
| 403 | caller has no tenant context, or `consultantId` is in another tenant |
| 404 | `topicId` not found in caller's tenant, or `consultantId` not found |

---

## 2. List invite links (used by all 3 tabs)

`GET /useradmin/invitelinks`

**Roles allowed:** `AUTHORIZATION_TENANT_ADMIN`, `AUTHORIZATION_USER_ADMIN`, `AUTHORIZATION_RESTRICTED_AGENCY_ADMIN` within their normal read scope.

**Query params (all optional):**

| Param | Type | Example | Notes |
|---|---|---|---|
| `linkKind` | enum | `EXTERNAL_INBOUND` | drives which tab the FE is on |
| `topicId` | long | `17` | dropdown filter |
| `chatType` | enum | `LIVE_CHAT` | filter |
| `status` | enum | `ACTIVE` / `USED` / `EXPIRED` | filter |
| `page` | int | `0` | 0-indexed, default 0 |
| `size` | int | `20` | default 20, clamped to 1–100 |

Backend **always** filters by caller's `tenantId` (from `X-Tenant-Id`) — no parameter for this; you cannot opt out.

**Response `200 OK`:**

```json
{
  "content": [
    { /* AgencyInviteLinkResponseDTO — same shape as §1 response */ }
  ],
  "totalElements": 117,
  "totalPages": 6,
  "page": 0,
  "size": 20
}
```

The result is sorted by `createDate` descending. Links whose `expiresAt` has passed are
auto-flipped to `status = EXPIRED` on read, so the UI always shows fresh status without you doing
date math.

---

## Manage an external invite link

Both operations require the same authentication and tenant context as creation. Only a platform admin can perform either operation on a live-chat link.
The link must be an `EXTERNAL_INBOUND` link in the caller's tenant and within the caller's
admin scope.

| Request | Success | Other responses |
|---|---|---|
| `POST /useradmin/invitelinks/{linkId}/reactivate` | `200 OK` with the link response from §1. Its status is `ACTIVE` and its token stays the same. Repeating the request on an active, unexpired link returns the same link. | `409 Conflict` for expired links or statuses other than `ACTIVE` and `DISABLED`. |
| `DELETE /useradmin/invitelinks/{linkId}` | `204 No Content` for a link in any status. Its URL stops resolving. Conversations already started through the link keep their own sessions. | A repeat request returns `404 Not Found`. |

Both requests return `401` without authentication, `403` outside the caller's admin scope,
and `404` for an unknown link, a link in another tenant, or another link kind. A platform caller
without a valid positive tenant ID receives `403`.

---

## 3. Redeem invite link (PUBLIC — no auth)

`POST /users/invitelinks/{token}/redeem`

The visitor calls this endpoint without `Authorization` or `X-Tenant-Id`. For Live Chat, an
optional JSON body can supply the rolled `username`. If absent, the service chooses the handle.

**Live Chat response `200 OK`:**

```json
{
  "sessionId": 9012,
  "userName": "Anonymous-x9k",
  "accessToken": "<oriso-access-token>",
  "refreshToken": "<oriso-refresh-token>",
  "expiresIn": 3600,
  "refreshExpiresIn": 1800,
  "tenantId": 12,
  "agencyId": null,
  "consultingTypeId": 3,
  "topicId": 17
}
```

The example shows `RedeemResponseDTO` for a `LIVE_CHAT` link. It contains ORISO session credentials
and invite routing fields. The frontend initializes Matrix through its normal host bootstrap.
There are no chat-provider credentials or Matrix identifiers in the response.

A successful `LIVE_CHAT` redeem creates an anonymous session without a second registration call.
For a registered invitation, the session and authentication fields are null and the routing fields
guide registration. A call share link is handled by the separate call entry flow. The former public
`POST /conversations/askers/anonymous/new` route was removed in ORISO-UserService#1697.

**Errors:**

| Code | Reason |
|---|---|
| 400 | link inactive / expired / missing required routing metadata |
| 404 | token doesn't exist |
| 410 | paused live-chat link (`INVITE_LINK_PAUSED`), switched-off direct link (`DIRECT_LINK_INACTIVE`), or expired or replaced scheduled direct link (`DIRECT_LINK_EXPIRED`) |

The public `GET /users/invitelinks/{token}/context` returns the same paused-link reason before
providing entry metadata. Neither route creates an account or conversation while the link is paused.
A switched-off direct link retains `{"reason":"DIRECT_LINK_INACTIVE"}`.

The token lookup uses a pessimistic lock, but a successful redeem deliberately leaves the link
`ACTIVE`. Published links are reusable until expiry so multiple visitors can enter through the
same link. A `LIVE_CHAT` request creates a new anonymous session for each successful redeem.

---

## 4. Helpers (already exist — no work for backend, just listed for completeness)

| Purpose | Endpoint |
|---|---|
| Topic dropdown (tenant admin)        | `GET /service/topic/` |
| Topic dropdown (super admin)         | `GET /service/topicadmin` |
| Consultant dropdown (Counsellor tab) | `GET /service/useradmin/consultants` |

---

## 5. Open questions left for product (NOT blocking the FE)

These are intentionally **not** part of this delivery — they are flagged for the next sprint:

- Whether to expose a real "all tenants" cross-tenant list view for super-admin (today they switch tenants via `X-Tenant-Id`).
- Whether to add a per-topic default `consultingType` — today the backend picks the tenant's first.
- Whether to add a topic-based counsellor pool query — today the notification fan-out uses the existing consulting-type-based pool.

None of these change the endpoints above.
