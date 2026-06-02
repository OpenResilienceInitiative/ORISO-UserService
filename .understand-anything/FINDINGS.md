# ORISO UserService Findings

## Navigation

- [Missing Documentation](#missing-documentation)
- [Dead Or Suspicious Files](#dead-or-suspicious-files)
- [Risky Dependencies](#risky-dependencies)
- [Unclear Boundaries](#unclear-boundaries)
- [Duplicated Or Repeated Logic](#duplicated-or-repeated-logic)
- [Recommended Cleanup Order](#recommended-cleanup-order)

## Missing Documentation

- The root documentation is inconsistent: git tracks both `README.md` and `readme.md`, and the current working-tree content differs from the committed `README.md`.
- There is no single local onboarding guide that explains generated API contracts, Maven codegen, Keycloak roles, tenant behavior, and required external services together.
- The Matrix migration behavior is visible in code comments and logs in `CreateUserFacade.java`, but it needs a short architecture note covering expected fallback semantics.
- Deployment assumptions are split across `application.properties`, `Dockerfile`, `deploy-development.sh`, and root README content. The active Kubernetes manifests are not in this repository.
- Liquibase status is ambiguous: changelogs exist here, while the root README diff says schemas may be managed by `ORISO-Database`.

## Dead Or Suspicious Files

- `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/UserController.java.backup` is tracked but not part of Java compilation.
- `cookies.txt` is tracked and should be reviewed for accidental local/session data.
- `.DS_Store` exists in the working tree and should stay untracked/ignored.
- `README.md` and `readme.md` are both tracked. On case-insensitive filesystems this is fragile and can hide changes.

## Risky Dependencies

This is a static repository review, not a live CVE scan. Run a Maven/OWASP or enterprise SCA scan before release.

- Spring Boot 2.7.x and Spring Security 5.x are old platform lines and make upgrades harder as peer libraries move to Spring Boot 3/4.
- `keycloak-spring-security-adapter` and `keycloak-spring-boot-starter` are from the legacy Keycloak adapter model; future work should plan migration to Spring Security OAuth2 resource server patterns.
- Springfox 2.x is a legacy Swagger stack; OpenAPI Generator already exists and should be the preferred API-contract path.
- PowerMock 2.x and JUnit4-era patterns increase test maintenance cost with newer Java and Mockito versions.
- H2 1.4.200 is test scoped but old; keep it isolated from production packaging.
- `json-smart`, `jsoup`, `firebase-admin`, `commons-csv`, `passay`, `logstash-logback-encoder`, and Log4j pinning should be checked by a live dependency scanner.

## Unclear Boundaries

- `UserController.java` is very large and handles many unrelated flows: registration, sessions, chats, notifications, password, email, mobile tokens, archive, 2FA, and consultant data.
- `CreateUserFacade.java` currently coordinates Keycloak, MariaDB, Matrix, session creation, statistics, tenant/agency lookup, rollback, and fallback behavior. It is a high-change-risk file.
- Messaging is split between Rocket.Chat and Matrix, with migration/fallback logic embedded in user/session flows. Document which backend is authoritative for each feature.
- Tenant context is a thread-local cross-cutting concern. Any async work or scheduled flow needs explicit tenant assumptions.
- Generated API contracts and hand-written custom controllers coexist. New endpoints need a clear rule: OpenAPI-first or hand-written only when there is a deliberate exception.

## Duplicated Or Repeated Logic

- Authorization rules are centralized but large in `SecurityConfig.java`; endpoint additions require duplicated path knowledge across OpenAPI and security matchers.
- Admin create/update/search flows repeat patterns across agency admins, tenant admins, consultants, and askers.
- External client factories under `config/apiclient` repeat setup patterns for generated APIs.
- Notification email suppliers share tenant template and content-building behavior that should stay consistently factored.
- Workflow deletion actions repeat external-system deletion semantics for asker and consultant targets.

## Recommended Cleanup Order

1. Resolve the tracked `README.md` / `readme.md` conflict and keep one canonical root README.
2. Remove or archive `UserController.java.backup` outside compiled source.
3. Review and remove `cookies.txt` if it is local data.
4. Add a short `docs/` hub for codegen, auth, tenancy, messaging migration, and local run dependencies.
5. Run a live dependency scan and convert this static dependency list into tracked upgrade tickets.
6. Split new behavior away from `UserController.java` and `CreateUserFacade.java` rather than growing them further.

