# AGENTS.md — ORISO-UserService

Load workspace parent `../AGENTS.md` first (`PROJECT_ORISO_ROOT` = parent of this repo).

## Stack

Java **21**, Spring Boot **4.0.7**, Maven Wrapper **3.9.15**. Owns user/consultant lifecycle, sessions, and related `/service/users` / useradmin APIs.

## Commands

```bash
./mvnw -B test
./mvnw -B package -DskipTests
./mvnw -B spotless:check   # format gate (present in pom; not always in CI)
```

From this repository root (so `../scripts` resolves to the workspace harness):
`REPO=ORISO-UserService ../scripts/harness/verify-fast.sh` (or `verify-full.sh`).
From `PROJECT_ORISO_ROOT`: `REPO=ORISO-UserService ./scripts/harness/verify-fast.sh`.

CI (GitHub Actions): `./mvnw -B test` then `./mvnw -B package -DskipTests` on Java 21.

## Context

- Integration branch: `pre-dev` when used for ORISO feature work.
- Skim `.understand-anything/` before non-trivial changes; verify graph freshness.
- Do not invent DTOs/OpenAPI — read existing controllers and generated clients.
- Secrets: prefer `config.env.example`; never commit `config.env` or logs with tokens.

## Done

Targeted tests for touched behavior pass; package succeeds for PR-bound work; no secrets in the diff. Task notes: `docs/agent-tasks/YYYY-MM-DD_short-name/` if needed.
