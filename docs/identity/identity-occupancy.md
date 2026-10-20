# Exact identity occupancy

`POST /service/useradmin/identity-observations` is a read-only collision check. The service context path is `/service`; the generated operation is `/useradmin/identity-observations`. Only a nontechnical platform administrator with `USER_ADMIN` can reach the lookup. Tenant zero alone grants no access.

The response scope is always `identity-occupancy-v1`. ID, username and email selectors form a union across the configured Keycloak realm and raw admin, consultant and asker rows. Disabled provider identities and soft-deleted relational rows still occupy an identity. Admin has no deletion column, so its deletion state is unknown. No agency or role-grant inventory is implied.

Underscores are literal selector characters, including canonical `_at_` usernames. Selectors never become query patterns; `%`, `*`, `?` and control characters are rejected.

Only a complete, empty observation can return `200` / `FREE`. Multiple matching identity IDs conflict (`409`). Reaching a bound also returns `409` with an incomplete observation. A failed dependency returns `502`; already observed positive facts are retained. Provider and database details are not exposed. Every response from the observation adapter uses `Cache-Control: no-store`.

Fixed bounds are 20 distinct identities, 21 projected rows per relational exact query, and 100 candidates per page for at most 20 pages per provider query or relational encoded-username scan. A full last permitted candidate page is incomplete even when postfiltering yields no matches. Username comparison decodes the existing `enc.` Base32 representation before comparing without case sensitivity. Exact raw/encoded lookups are supplemented by a bounded encoded-candidate scan: fixed `enc.` username search in Keycloak and fixed encoded-prefix SQL in each relational layer. Candidate counts are separate from the 20 matching-identity bound. Malformed encodings make the layer incomplete. Only exact decoded matches enter the response. Relational queries use fixed identifiers and bound parameters, with no active/deleted filter or ORM association loading. They do not change the caller's tenant context.

This endpoint creates or changes nothing. Observation is not an ownership receipt, a creation reservation, a complete resource inventory, proof of physical absence, or permission to release a fixture lease. Matrix, sessions, agencies, grants, creation races and lifecycle recovery require their own authoritative checks.
