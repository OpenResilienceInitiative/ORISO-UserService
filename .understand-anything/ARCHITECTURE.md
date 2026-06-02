# ORISO UserService Architecture

## Navigation

- [Purpose](#purpose)
- [Architecture Layers](#architecture-layers)
- [Major Modules](#major-modules)
- [Data Flow](#data-flow)
- [Authentication Flow](#authentication-flow)
- [API Structure](#api-structure)
- [Dependencies](#dependencies)
- [Deployment And Config](#deployment-and-config)
- [Top Files](#top-files)

## Purpose

ORISO UserService is the user/session lifecycle backend for the Online-Beratung platform. It manages askers, consultants, admins, sessions, conversations, chat setup, notifications, account deletion, inactive-account notifications, and integration with identity and messaging services.

The repository is a Spring Boot service rooted at `src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java`. Maven drives compilation, OpenAPI code generation, tests, profiles, and packaging through `pom.xml`.

## Architecture Layers

- API HTTP adapters: `adapters/web/controller/*` implements generated OpenAPI contracts and custom REST endpoints.
- Security and tenancy: `config/auth/SecurityConfig.java`, `StatelessCsrfFilter.java`, `HttpTenantFilter.java`, and `tenant/*` protect requests and establish tenant context.
- Facades and use cases: `facade/*`, `conversation/facade/*`, and `admin/facade/*` orchestrate multi-step business flows.
- Domain services: `service/*`, `admin/service/*`, `conversation/service/*`, and `manager/*` contain reusable business operations.
- External adapters: `adapters/keycloak/*`, `adapters/rocketchat/*`, `adapters/matrix/*`, `config/apiclient/*`, and `services/*.yaml` connect to ORISO peer services.
- Persistence and data: `model/*`, `port/out/*Repository.java`, and `src/main/resources/db/changelog/*` own database state and schema evolution.
- Scheduled workflows: `workflow/delete/*`, `workflow/deactivate/*`, `workflow/enquirynotification/*`, and `workflow/inactiveaccountnotification/*` run background lifecycle jobs.
- Runtime configuration: `application*.properties`, `Dockerfile`, `deploy-development.sh`, `logback-spring.xml`, and monitoring JSON files define operational behavior.

## Major Modules

- Registration and session lifecycle: `UserController.java`, `CreateUserFacade.java`, `CreateNewSessionFacade.java`, `CreateSessionFacade.java`.
- Assignment and consultant workflows: `AssignSessionFacade.java`, `AssignEnquiryFacade.java`, `SessionToConsultantVerifier.java`.
- Admin API: `UserAdminController.java`, `AdminUserFacade.java`, `ConsultantAdminFacade.java`, `AskerUserAdminFacade.java`.
- Conversations: `ConversationController.java`, `ConversationListResolver.java`, `ConversationListProviderRegistry.java`, anonymous/registered/archive providers.
- Chat and messaging: `RocketChatService.java`, `RocketChatFacade.java`, `MatrixSynapseService.java`, `MatrixMessageController.java`, `MatrixSyncController.java`.
- Persistence: JPA entities such as `User.java`, `Consultant.java`, `Session.java`, `Chat.java`, and repositories under `port/out`.
- Deletion and retention: schedulers/services/actions under `workflow/delete` and `workflow/deactivate`.
- Notifications: `EmailNotificationFacade.java`, `EventNotificationService.java`, `MobilePushNotificationService.java`, inactive account and enquiry notification workflows.

## Data Flow

Registration enters through `UserController.registerUser`, validates the request, creates identity in Keycloak through `IdentityClient` / `KeycloakService`, persists a `User`, optionally provisions Matrix user state, creates a `Session` through `CreateSessionFacade`, stores `SessionData`, and emits registration statistics.

Session assignment enters through `UserController.assignSession`, loads session and consultant state, validates assignment rules, updates session status, updates Rocket.Chat membership, removes unauthorized consultants, sends reassignment mail, and emits statistics.

Anonymous conversations enter through `ConversationController.createAnonymousEnquiry`, validate the consulting type, generate anonymous credentials, create Keycloak/MariaDB/Rocket.Chat state, create the anonymous session, and return access/refresh/Rocket.Chat credentials to the caller.

Scheduled deletion workflows read users/sessions from repositories, run action registries for Keycloak, Rocket.Chat, appointment service, database rows, and room/session cleanup, then write workflow error records or tombstones as needed.

## Authentication Flow

`SecurityConfig.java` extends Keycloak's Spring Security adapter. It disables default session-backed CSRF, adds `StatelessCsrfFilter`, optionally adds `IpPrivacyHeaderFilter`, then enables `HttpTenantFilter` when multitenancy is active.

The service is bearer-only through `keycloak.bearer-only=true` in `application.properties`. Keycloak roles are mapped by `RoleAuthorizationAuthorityMapper.java` and checked with explicit `antMatchers` in `SecurityConfig.java`.

Tenant resolution is request scoped. `HttpTenantFilter.java` asks `TenantResolverService.java`, which tries technical/super-admin, access-token claims, custom header, single-domain rules, and subdomain resolution depending on authentication state and feature flags.

Public or semi-public paths include registration, anonymous enquiry creation, magic-link request/consume, docs/health endpoints, Matrix sync registration, consultant public data, invite-link redemption, and selected session room lookups. All unmatched requests end in `denyAll()`.

## API Structure

Inbound API contracts live under `api/`:

- `api/userservice.yaml`: user registration, sessions, chats, notifications, password, 2FA, consultant data, import, and live proxy operations.
- `api/useradminservice.yaml`: admin root, sessions, consultants, askers, agency admins, tenant admins, relation management, and violation reports.
- `api/conversationservice.yaml`: consultant enquiry lists, anonymous enquiry creation/acceptance/finish, and archive views.
- `api/appointmentservice.yaml`: appointment CRUD and appointment-backed enquiry creation.
- `api/userstatisticsservice.yaml`: user statistics endpoint.

Controllers in `adapters/web/controller` implement these generated interfaces and also define custom endpoints for Matrix, draft messages, event notifications, supervisor logs, invite links, inactive account audit logs, version, and SMTP test email.

## Dependencies

The service depends on Spring Boot, Spring Security, Spring Data JPA, Keycloak adapter/client, OpenAPI Generator, Liquibase, MariaDB, Rocket.Chat REST/Mongo access, Matrix Synapse REST access, RabbitMQ, Redis, Firebase Admin, Springfox, Hibernate Search, and test tooling such as Testcontainers, H2, Mockito, PowerMock, and Awaitility.

Outbound ORISO service clients are generated from `services/*.yaml` for agency, tenant, consulting type, topic, application settings, appointment, mail, message, live, statistics, and Keycloak extension APIs.

## Deployment And Config

`application.properties` is the central defaults file. It expects runtime values through environment variables for database, Keycloak, Rocket.Chat, Matrix, RabbitMQ, SMTP, and peer service URLs.

`Dockerfile` runs `UserService.jar` with Java 11 and optional JDWP debugging. `deploy-development.sh` builds the Maven package, copies `target/UserService.jar`, builds/imports a Docker image into k3s, and restarts the Kubernetes deployment.

Liquibase changelog masters are under `src/main/resources/db/changelog/userservice-*-master.xml`. The root README currently says database schemas may be managed by a separate ORISO-Database repository; verify the active environment before enabling migrations.

## Top Files

1. `src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java`
2. `src/main/java/de/caritas/cob/userservice/api/config/auth/SecurityConfig.java`
3. `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/UserController.java`
4. `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/UserAdminController.java`
5. `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/ConversationController.java`
6. `src/main/java/de/caritas/cob/userservice/api/facade/CreateUserFacade.java`
7. `src/main/java/de/caritas/cob/userservice/api/facade/CreateSessionFacade.java`
8. `src/main/java/de/caritas/cob/userservice/api/facade/assignsession/AssignSessionFacade.java`
9. `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/KeycloakService.java`
10. `src/main/java/de/caritas/cob/userservice/api/adapters/rocketchat/RocketChatService.java`

