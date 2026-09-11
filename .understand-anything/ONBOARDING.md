# Onboarding Guide: ORISO-UserService

1. Read `README.md` in the repository root — it states the current purpose: user/consultant accounts, enquiries, sessions, session lists, and the lifecycle of encrypted **Matrix** rooms (Rocket.Chat is gone).
2. Open `.understand-anything/README.md` and launch the dashboard using the command shown there.
3. Start with these tour files:

- `README.md` - current service purpose and consultation kinds (1:1, team, group chat, anonymous).
- `pom.xml` - Java 21, Spring Boot 4.0.7 parent, OAuth2 resource server, Keycloak admin-client 26, OpenAPI Generator for `api/` and `services/` specs.
- `api/userservice.yaml` - the main OpenAPI spec this service implements (plus `useradminservice`, `conversationservice`, `appointmentservice`, `userstatisticsservice`).
- `src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java` - Spring Boot entry point (`@EnableAsync`, `@EnableScheduling`).
- `src/main/java/de/caritas/cob/userservice/api/AccountManager.java` and `Messenger.java` - hexagonal core managers behind `port/in`.
- `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/` - the ~38 REST controllers (UserController + delegates, Matrix, invites, handshake, support rooms).
- `src/main/java/de/caritas/cob/userservice/api/adapters/matrix/` - Matrix Synapse adapter (room, media, assignment gateways).
- `src/main/java/de/caritas/cob/userservice/api/port/out/` - repositories, identity ports, and messaging gateways.
- `src/main/resources/db/changelog/userservice-master.xml` - single Liquibase master changelog (89 changesets, `0001`-`0085`).
- `docs/` and `documentation/` - operational contracts (`replica-safety.md`, `schema-migrations.md`, `api-error-contract.md`) and ADRs (`ADR-SECURITY-02-unified-crypto-boundary.md`).
- `documentation/local-development.md` - how to run the service locally.

4. Review architecture layers in `.understand-anything/ARCHITECTURE.md`.
5. For changes, inspect files connected by `imports`, `configures`, `routes`, `deploys`, and `tested_by` edges in the graph.
