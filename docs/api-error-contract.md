# API error contract

Workstream 2 of epic
[#351](https://github.com/OpenResilienceInitiative/ORISO-UserService/issues/351).

## Why this exists

The production incidents of 2026-07-08 shared one shape: a multi-step operation
left the system inconsistent, and that inconsistency was either **swallowed**
(the caller got `200` and nothing had happened) or **hard-crashed** (`500` on a
state the API should treat as normal). Neither told the caller what to do.

Two concrete cases:

- `setConsultantAgencies` always answered `200`, while the ADR-003 topic
  validator had rejected the assignment. The Admin showed success; nothing was
  persisted.
- `/users/data` answered `500` for a consultant without an agency. "No agency
  yet" is an expected state, not a server fault, and the whole app died on it.

## The contract

| Situation | Status | Body |
|---|---|---|
| Input fails validation | `400` | message describing what is wrong |
| Caller is authenticated but not allowed | `403` | none |
| Addressed entity does not exist | `404` | none |
| Request conflicts with current state (duplicate, concurrent change) | `409` | machine-readable reason where one exists |
| A downstream service the call depends on failed | `424` / `502` | reason header or body |
| Operation succeeded, nothing to return | `204` | none |
| **Expected** empty state (no agencies, no sessions, no appointments yet) | `200` | the empty collection or an object with empty fields |
| Everything else | `500` | none |

Three rules follow from it, and they are the ones that were broken:

1. **A failure never answers `2xx`.** If any step of a multi-step operation
   fails, the response is `4xx`/`5xx`. A partial success is a failure.
2. **An expected empty state never answers `5xx`.** "The user has no X yet" is
   `200` with an empty payload. Reserve `500` for faults the caller cannot
   act on.
3. **Response bodies stay information-poor on purpose.** `ApiResponseEntityExceptionHandler`
   deliberately withholds internal detail from `403`/`404`/`409` to avoid
   leaking structure or existence. Put the diagnostic detail in the log, not in
   the response.

The exception-to-status mapping is enforced by
`ApiResponseEntityExceptionHandlerTest#mappedExceptionHandlers_returnExpectedHttpStatus`.
Add a row there whenever you add an exception type.

## Review checklist

Run through this on any PR that touches a controller, a facade, or a saga:

- [ ] Does every `catch` block either rethrow, translate to an
      `httpresponses` exception, or have a comment saying why swallowing is
      correct here? A bare `catch (Exception e) { log.error(...) }` before a
      `return` is a `200`-on-failure bug.
- [ ] Does the operation touch more than one system (DB + Keycloak + Matrix +
      another service)? If so, is there a rollback path, and is a failed
      rollback surfaced rather than logged and forgotten?
- [ ] Is any `500` in this diff reachable from an empty-but-valid state
      (no agency, no session, no consultant)? Then it should be `200` with an
      empty payload, or `404` if the addressed entity truly does not exist.
- [ ] Do new validation failures produce `400` with a message the Admin or the
      frontend can show, rather than a silent no-op?
- [ ] Does a new endpoint appear in the mapping test above?
- [ ] Are new required config values registered in `ConfigurationValidator`, so
      a missing one fails at startup instead of as a runtime `500`
      (workstream 3)?
