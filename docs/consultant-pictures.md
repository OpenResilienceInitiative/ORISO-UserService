# Internal consultant pictures

Issue #1048 adds a UserService-owned private picture for each consultant. The [OpenAPI
contract](../api/useradmin-consultant-picture.yaml) is linked from the existing useradmin
specification. It is deliberately not generated into `UseradminApi`: generated binary
`RequestBody` conversion would run before the controller's target check, and inherited
default routes would duplicate the dedicated raw-body controller.

## Access and lifecycle

The same endpoint exists under `/useradmin` and `/service/useradmin`:
`/consultants/{consultantId}/picture`. Eligible colleagues/admins may GET; callers with
`CONSULTANT_UPDATE` may PUT or DELETE. The service separately checks the active target,
the authenticated caller's tenant, and a restricted agency admin's target agencies.
A verified platform admin is the explicit tenant exception. Technical authority without
an eligible tenant is insufficient. Advice-seeker authentication alone gives no access.
The public consultant profile endpoint and normal consultant DTOs are unchanged.

PUT accepts a raw JPEG or PNG body, never multipart. The byte limit is 5 MiB (5,242,880);
each dimension is at most 4096 and decoded pixels at most 12,000,000. The intake checks
the actual stream, PNG chunk/JPEG marker structure through EOF and full decode. It uses memory-only ImageIO
input, and an exact-route filter refuses multipart before servlet parsing. Two upload
slots bound concurrent intake/decoding/scanning. Only the exact bytes approved by ClamAV
are stored. Failed uploads leave previous bytes intact.

The database has one `consultant_picture` row per consultant, a bounded MEDIUMBLOB,
canonical MIME and update time. Upload scanning happens outside a database transaction.
After scanning, a fresh `SELECT FOR UPDATE` reloads the owner before checking ownership
and replacing the row. This also handles a stale managed owner in an open entity manager:
Hibernate's refresh of an already locked entity can issue a non-locking read, which is
insufficient under MariaDB repeatable-read isolation.

The Admin soft-delete transaction takes the same owner lock before pre-deletion work and
removes the picture immediately. Final hard deletion has an `ON DELETE CASCADE` safeguard.
The existing account safeguard/hold workflow remains in place. GET uses true MIME,
`X-Content-Type-Options: nosniff` and `Cache-Control: no-store, private`.

## Scanner deployment contract

New uploads are refused by default; GET and removal of already stored images remain usable.
The separate Helm change must enable a UserService-local loopback-only ClamAV sidecar.
No new shared service, remote credential, Matrix media path or exposed scanner port is used.

| Environment variable | Default | Constraint |
| --- | --- | --- |
| `CONSULTANT_PICTURE_SCANNER_ENABLED` | `false` | Explicitly enable once scanner is configured |
| `CONSULTANT_PICTURE_SCANNER_PORT` | `3310` | 1..65535, host fixed to `127.0.0.1` |
| `CONSULTANT_PICTURE_SCANNER_TIMEOUT_MILLIS` | `5000` | 100..30000; total connect/write/read deadline |

These map to `consultant.picture.scanner.enabled`, `.port`, and `.timeout-millis`.
Clamd must use compatible limits of at least 5 MiB for INSTREAM and file scanning,
`AlertExceedsMax yes`, and bounded memory-backed temporary scan storage. Only exact
`stream: OK` permits a write; FOUND refuses, all malformed/error/truncated/timeout
responses refuse. Scanner diagnostics are never logged or returned. The scanner should
not gate ordinary UserService readiness during signature warm-up/reload.

Source tests use synthetic TCP verdicts and synthetic pictures. Real ClamAV signatures,
operator capacity, the Helm opt-in and deployed Admin reload/deletion acceptance are
separate delivery gates; local Java/database tests do not establish them.

## Local checks

Unit gate: `./mvnw -B test` on Java21. The focused HTTP suite builds the actual
`SecurityConfig` filter chain, including both prefixes; it mocks the authenticated-user
adapter and storage, not the route or target authorization logic. A separate JWT HTTP
suite uses the actual bearer filter, role conversion, request-scoped caller construction
and tenant filter with synthetic decoded tokens; only token verification is stubbed.

The MariaDB tests apply the complete Liquibase master and validate all mapped entities.
Provide an isolated empty database named `userservice`, then run:

```sh
LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:33348/userservice \
  ./mvnw -B test -Dtest=ConsultantPictureDatabaseIT,DatabaseChangelogDriftIT
```

Disposable test credentials default to `root`/`root`, matching the existing drift test.
These are not production credentials. The database suite verifies real blob persistence,
failed replacement, size/FK checks, immediate soft deletion and both race orderings.
