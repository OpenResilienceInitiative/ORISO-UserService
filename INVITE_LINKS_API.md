# Invite Links API — for the Frontend Developer

> Backend branch: `backend-of-invite-link` (off `Rebuild`)
> Service: `ORISO-UserService`
> Status of redesign: agency removed from invite links — topic is the core identifier.

This document is the **contract**. You can build the UI against it without reading any backend
code. If something here disagrees with what the backend does, that's a backend bug — file an
issue, do not work around it in the frontend.

---

## Base URL

| Environment | URL |
|---|---|
| Local dev (laptop) | `http://localhost:8080` |
| Dev cluster        | `https://api.oriso-dev.site` |

All authenticated endpoints expect:

- `Authorization: Bearer <keycloak access token>`
- `X-Tenant-Id: <numeric tenant id>` (required for tenant-scoped admins; super-admin uses it to impersonate a tenant)
- `Content-Type: application/json`

The redeem endpoint (§3) requires **none** of these.

---

## 1. Create invite link

`POST /useradmin/invitelinks`

**Roles allowed:** `AUTHORIZATION_TENANT_ADMIN`, `AUTHORIZATION_USER_ADMIN`, `AUTHORIZATION_RESTRICTED_AGENCY_ADMIN`

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

**Roles allowed:** same as create.

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

## 3. Redeem invite link (PUBLIC — no auth)

`POST /users/invitelinks/{token}/redeem`

**No** `Authorization`, **no** `X-Tenant-Id`. This is the endpoint the end-user (anonymous visitor) hits when they open the link.

**Response `200 OK`:**

```json
{
  "sessionId": 9012,
  "userName": "Anonymous-x9k",
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "expiresIn": 3600,
  "refreshExpiresIn": 1800,
  "rcUserId": "rcUser-9012",
  "rcToken": "rc-token-...",
  "rcGroupId": "rc-group-..."
}
```

This is the **same shape** `CreateAnonymousEnquiryResponseDTO` already returns from
`POST /conversations/askers/anonymous/new`. The visitor is now a logged-in anonymous user with a
live-chat session ready — drop them straight into the chat UI; no second registration call.

**Errors:**

| Code | Reason |
|---|---|
| 400 | link already used / expired / never had a topic |
| 404 | token doesn't exist |

The token lookup uses a pessimistic lock; double-clicks and parallel calls cannot redeem the same
link twice.

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
