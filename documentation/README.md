# UserService documentation

This directory contains current, repository-owned contracts for the Matrix-only UserService.
Production behavior is defined by the code, executable contract tests, OpenAPI specifications, and
the documents linked below. Unlinked diagrams are not an architectural source of truth.

## Architecture and security

- [`ADR-SECURITY-02-unified-crypto-boundary.md`](ADR-SECURITY-02-unified-crypto-boundary.md) — the
  frontend host owns Matrix encryption; UserService never handles client crypto keys.
- [`MATRIX_SYNC_OBSERVABILITY.md`](MATRIX_SYNC_OBSERVABILITY.md) — Matrix synchronization signals,
  metrics, and operational interpretation.
- [`GROUP_CHAT_DEACTIVATION_REPLICA_SAFETY.md`](GROUP_CHAT_DEACTIVATION_REPLICA_SAFETY.md) —
  Matrix-only group-chat deactivation and lease behavior.
- [`USER_SERVICE_REPLICA_SAFETY.md`](USER_SERVICE_REPLICA_SAFETY.md) — distributed-state and
  replica-safety inventory.
- [`USER_SERVICE_STABILITY.md`](USER_SERVICE_STABILITY.md) — current stability boundaries and
  verified failure classes.

## Local development and API checks

- [`local-development.md`](local-development.md) — Java 21 local setup and remote development
  dependencies.
- [`local-invite-link-api-testing.md`](local-invite-link-api-testing.md) — invite-link verification
  against the current controller contract.
- [`BRANDED_EMAIL_LAYOUT.md`](BRANDED_EMAIL_LAYOUT.md) — branded email layout contract.
- [`postman/`](postman/) — local API collections without committed credentials.

## Historical evidence

`user-service-historical-failure-classification.json`, the repository changelog, and irreversible
Liquibase migrations intentionally retain names of removed transports where needed to explain or
verify the migration. Negative contract tests may also name deleted components to prevent their
return. Those references are evidence, not runtime integrations.

Obsolete diagrams that presented Rocket.Chat or LiveService as active architecture were removed
from the current tree. Git history remains the recovery source if an old diagram is needed for an
audit.

Future Jitsi, Google Meet, or Microsoft Teams support is not prohibited by this cleanup. Such video
providers belong behind separately reviewed provider contracts and must not become a hidden
fallback or weaken the Matrix/Element Call encryption boundary. UserService currently exposes no
Jitsi runtime path.
