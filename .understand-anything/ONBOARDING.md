# ORISO UserService Developer Onboarding

## Navigation

- [First Read](#first-read)
- [Run And Build](#run-and-build)
- [How To Trace A Request](#how-to-trace-a-request)
- [Where To Change Things](#where-to-change-things)
- [Testing Map](#testing-map)
- [Common Pitfalls](#common-pitfalls)

## First Read

Start with these files in order:

1. `README.md` and `readme.md` to understand the existing project-level documentation split.
2. `pom.xml` to understand generated sources, service clients, Java version, profiles, and test separation.
3. `src/main/resources/application.properties` to understand runtime dependencies and feature flags.
4. `src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java` for the Spring Boot entry point.
5. `src/main/java/de/caritas/cob/userservice/api/config/auth/SecurityConfig.java` before changing any endpoint.

## Run And Build

Common local commands:

```bash
./mvnw clean test
./mvnw spring-boot:run -Dspring-boot.run.profiles=local -DskipTests
./mvnw clean package -DskipTests
```

The service listens on `server.port=8082` by default. Health is exposed at:

```bash
curl http://localhost:8082/actuator/health
```

This repository depends on external services. A productive local run needs MariaDB, Keycloak, Rocket.Chat, Matrix, RabbitMQ, and several ORISO service URLs depending on the flow being tested.

## How To Trace A Request

For generated API endpoints, start at the OpenAPI file under `api/`, find the `operationId`, then find the matching method in a controller under `adapters/web/controller`.

For registration:

`api/userservice.yaml` -> `UserController.registerUser` -> `CreateUserFacade` -> `KeycloakService` / `UserService` / `MatrixSynapseService` -> `CreateNewSessionFacade` -> `CreateSessionFacade` -> repositories and statistics.

For assignment:

`api/userservice.yaml` -> `UserController.assignSession` -> `AssignSessionFacade` -> `SessionService` -> `RocketChatFacade` / `RocketChatService` -> notification and statistics services.

For anonymous enquiry:

`api/conversationservice.yaml` -> `ConversationController.createAnonymousEnquiry` -> `CreateAnonymousEnquiryFacade` -> `AnonymousUserCreatorService` -> `AnonymousConversationCreatorService`.

## Where To Change Things

- Add or change public API shape in `api/*.yaml`, then regenerate generated sources through Maven.
- Add admin API behavior in `UserAdminController.java` plus the matching admin facade/service.
- Add user/session/chat behavior in `UserController.java` only as routing glue; keep orchestration in facades and business rules in services.
- Add persistence in `model/*`, `port/out/*Repository.java`, and Liquibase changelogs.
- Add outbound service integration by updating `services/*.yaml`, `pom.xml` generator config, and a factory/client wrapper under `config/apiclient`.
- Add authorization in `SecurityConfig.java` at the same time as adding an endpoint.
- Add tenant-aware behavior with explicit `TenantContext` assumptions and tests.

## Testing Map

The test suite is large and split across unit/integration-style tests under `src/test/java`.

- Controller behavior: `src/test/java/de/caritas/cob/userservice/api/adapters/web/controller/*`
- Facades: `src/test/java/de/caritas/cob/userservice/api/facade/*`
- Admin services: `src/test/java/de/caritas/cob/userservice/api/admin/service/*`
- Workflows: `src/test/java/de/caritas/cob/userservice/api/workflow/*`
- Repositories: `src/test/java/de/caritas/cob/userservice/api/port/out/*`
- External adapters: `KeycloakServiceTest.java`, `RocketChatServiceTest.java`, Matrix tests where present.

Before changing security, run the authorization tests around `UserControllerAuthorizationIT.java` and `ConversationControllerAuthorizationIT.java` if the local environment supports them.

## Common Pitfalls

- `SecurityConfig.java` uses explicit path matchers and ends with `denyAll()`; new endpoints will be blocked until mapped.
- The repository has both `README.md` and `readme.md` tracked in git, but on a case-insensitive filesystem they collapse to one working-tree file.
- `UserController.java.backup` is tracked but not compiled; do not use it as implementation source.
- Registration currently has Matrix and Rocket.Chat fallback paths that log errors and continue in some cases. Check business expectations before tightening failures.
- Tenant behavior changes need both authenticated and anonymous request tests.
- Some runtime defaults are empty and must be injected from Kubernetes or local environment variables.

