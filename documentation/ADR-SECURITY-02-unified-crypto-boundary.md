# ADR Security-02: Unified Cryptographic Boundary

## Status
Accepted (foundation).

## Context
The current platform includes two chat security paths:

- Legacy Rocket.Chat custom E2EE helpers in frontend code.
- Matrix-based transport where server-side integrations can process message content.

This mixed model creates governance and lifecycle ambiguity for key ownership, participant changes,
and historical visibility.

## Decision
For greenfield security work, the platform defines one canonical cryptographic model:

- Messages and attachments must follow one shared security policy boundary.
- Participant and device changes must trigger deterministic key lifecycle actions.
- Historical visibility is governed by cryptographic policy, not membership checks alone.
- Server-side diagnostics must never reintroduce plaintext expansion beyond explicit approved modes.

## Immediate Implementation Scope
This ADR establishes the baseline and introduces implementation guardrails that are safe to deploy
without breaking existing chat flows:

- Policy configuration remains explicit and environment-driven.
- Event-based hooks for identity and participant lifecycle are introduced incrementally.
- Existing message delivery remains operational while policy-aligned controls are added.

## Consequences
Positive:

- Reduces ambiguity around trust boundaries.
- Creates a stable base for Security-02 lifecycle APIs and rekey enforcement.
- Aligns message and media security expectations.

Trade-offs:

- Full cryptographic unification is a phased migration and cannot be completed in one release.
- Transitional paths need strict policy controls until end-state enforcement is complete.
