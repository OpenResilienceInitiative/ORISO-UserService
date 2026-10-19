# Architecture Notes: ORISO-UserService

_Refreshed 2026-08-27 against pre-dev commit `285f7582` (1541 analyzed files)._

## Purpose

The UserService owns user and consultant accounts, enquiries, counselling sessions, session lists, and the application-side lifecycle of Matrix rooms. It covers registration of new askers, creation of enquiries and their encrypted Matrix rooms, assignment of consultants (including Matrix room memberships), the different consultation kinds (1:1, team, group chat, anonymous), and account-lifecycle workflows (deactivation, deletion, notifications). Rocket.Chat is gone: there is no Rocket.Chat code left under `src/main/java`, and Liquibase changesets `0073`–`0075` removed the Rocket.Chat user/room/feedback-room ID columns from the schema.

## Tech Stack

- Java 21, Spring Boot **4.0.7** (Spring Framework 7.0.x), built with Maven (`pom.xml`, group `de.caritas.cob`, artifact `userservice`).
- Runtime image: `eclipse-temurin:21-jre` (`Dockerfile`; Canonical's Pebble binary is stripped for CVE hygiene).
- Auth: Spring Security as an OAuth2 **resource server** (`spring-boot-starter-oauth2-resource-server`) against Keycloak 26.x; the deprecated Keycloak 17.x Spring adapters were removed. `keycloak-admin-client` 26.0.4 for admin operations.
- Persistence: MariaDB (`mariadb-java-client`) via Spring Data JPA; Liquibase 4.27 migrations; Redis (`spring-boot-starter-data-redis`) for caches, consultant live-availability and one-time tokens; Caffeine local caches. The MongoDB dependency and its auto-configuration exclusion are gone.
- Messaging/eventing: RabbitMQ (`spring-boot-starter-amqp`, statistics exchange `statistics.topic`), Firebase Admin for mobile push.
- Observability: `spring-boot-starter-opentelemetry` + Actuator (Sleuth/Zipkin were removed with the Boot 4 migration); `logstash-logback-encoder` for structured logs.
- OpenAPI Generator 7.17 generates both the server API from `api/*.yaml` and typed clients from `services/*.yaml`.

## Architecture Layers

### Api And Routing

`src/main/java/de/caritas/cob/userservice/api/adapters/web/` — controllers, DTOs, and mappings on top of the generated API. Roughly 38 controllers in `adapters/web/controller/`, including:

- `UserController.java` plus delegates split by concern (`UserAccountControllerDelegate`, `UserRegistrationControllerDelegate`, `UserSessionControllerDelegate`, `UserChatControllerDelegate`, `UserConsultantControllerDelegate`, `UserSupportControllerDelegate`, `UserTwoFactorAuthControllerDelegate`).
- `ConversationController.java`, `UserAdminController.java`, `AppointmentController.java`, `UserStatisticsController.java`, `AdminStatisticsController.java`, `ConsultantStatisticsController.java`.
- Matrix-facing endpoints: `MatrixMessageController.java`, `MatrixSyncController.java`, `MatrixRtcCallPolicyController.java`.
- Newer feature areas: `AccountInviteController.java`, `AgencyInviteLinkController.java`, `CaseHandoverController.java`, `HandshakeController.java`, `SupportRoomController.java`, `SupportAdminController.java`, `TeamDiscussionController.java`, `DraftMessageController.java`, `EventNotificationController.java`, `DoNotDisturbController.java`, `TutorialProgressController.java`, `ConsultantLiveAvailabilityController.java`, `TopicConsultantAvailabilityController.java`, `SessionSupervisorController.java`, `SupervisorLogsController.java`, `InactiveAccountAuditLogsController.java`, `DpaForwardEmailController.java`, `GlobalSmtpTestEmailController.java`, `ErrorReportController.java`, `IdAllocationController.java`, `TenantAdminOnboardingController.java`, `VersionController.java`, `DeprecatedLiveProxyController.java`.

### Application Core

`src/main/java/de/caritas/cob/userservice/api/` root holds the hexagonal core managers implementing the `port/in` interfaces:

- `AccountManager.java` (implements `AccountManaging`)
- `IdentityManager.java` (implements `IdentityManaging`)
- `Messenger.java` (implements `Messaging`)
- `Organizer.java` (implements `Organizing`)
- `PatchConsultantSaga.java`, `UserServiceMapper.java`, `UserServiceApplication.java` (`@EnableAsync`, `@EnableScheduling`).

Other top-level packages: `actions/` (command pattern for chat/session/user actions with an `ActionsRegistry`), `facade/` (use-case orchestration: `CreateUserFacade`, `CreateEnquiryMessageFacade`, `CreateSessionFacade`/`CreateNewSessionFacade`, chat facades, `EmailNotificationFacade`, `SessionSupervisorFacade`, `TeamDiscussionFacade`, plus `assignsession/`, `sessionlist/`, `userdata/`, `rollback/`), `service/` (domain services, see below), `admin/` (admin API facades, HAL links, violation reports), `conversation/`, `identity/`, `manager/`, `model/` (JPA entities), `container/`, `helper/`, `exception/`, `supervision/`, `tenant/` (multi-tenancy incl. `TenantHibernateInterceptor`), `scheduler/`, `workflow/`, `config/`.

### Ports (Hexagonal Boundary)

- `port/in/`: `AccountManaging`, `IdentityManaging`, `IdentityPolicy`, `Messaging`, `Organizing`.
- `port/out/`: ~70 interfaces — Spring Data repositories (`UserRepository`, `ConsultantRepository`, `SessionRepository`, `ChatRepository`, `AccountInviteRepository`, `CaseHandoverRequestRepository`, `HandshakeSessionRepository`, `SupportRoomRepository`, `TeamDiscussionRepository`, `DraftMessageRepository`, `EventNotificationRepository`, `TutorialProgressRepository`, …), the `IdentityClient` family of Keycloak-facing interfaces, and messaging gateways (`SessionRoomGateway`, `SessionAssignmentChatGateway`, `MatrixUserClient`).

### Adapters

`src/main/java/de/caritas/cob/userservice/api/adapters/` has exactly three adapters:

- `keycloak/` — `KeycloakClient`, `KeycloakService`, `KeycloakMapper`, config and DTOs; implements the identity ports.
- `matrix/` — the messaging backend: `MatrixRoomClient`, `MatrixMediaClient`, `MatrixSynapseService`, `MatrixSessionRoomGateway`, `MatrixSessionAssignmentGateway`, `MatrixUrlBuilder` plus config and DTOs. Configured via `matrix.apiUrl` (Synapse) in `application.properties`.
- `web/` — inbound HTTP (see Api And Routing).

### Domain Services

`src/main/java/de/caritas/cob/userservice/api/service/` mixes top-level services (`ChatService`, `ConsultantService`, `ConsultantAgencyService`, `SessionDataService`, `CaseHandoverService`, `DecryptionService`, `LogService`, …) with ~30 feature subpackages: `accountinvite`, `agency`, `agencyinvitelink`, `appointment`, `archive`, `auth`, `availability`, `chat`, `consultingtype`, `donotdisturb`, `draft`, `email`/`emailsupplier`, `erstantwort`, `handshake`, `identity`, `matrix`, `matrixrtc`, `mobilepushmessage`, `notification`, `provisioning`, `session`, `sessionlist`, `statistics`, `support`, `teamdiscussion`, `tutorial`, `user`, and helpers.

Matrix specifics: `service/matrix/` (`MatrixEventListenerService`, `GroupChatMembershipService`, `MatrixRoomMembershipProvider`, `MatrixSessionSystemMessageService`, `RedisMessageMirrorService`) and `service/matrixrtc/` (call/media policy for MatrixRTC: `MatrixRtcCallPolicyService`, policy token verification, denial reasons).

### Workflows

`src/main/java/de/caritas/cob/userservice/api/workflow/` — scheduled background workflows: `deactivate` (expired group chats, anonymous users), `delete` (account deletion), `enquirynotification`, `groupchatreminder`, `inactiveaccountnotification`, `notificationretention`, and `scheduling` (scheduled-task claiming backed by the `scheduled_task_claim` table for replica safety).

### Configuration

- `pom.xml`, `package.json` (commitlint/husky tooling only), `.mvn/wrapper/maven-wrapper.properties`.
- `api/*.yaml` — OpenAPI specs this service **implements**: `userservice.yaml` (main API, ~57 paths), `useradminservice.yaml`, `conversationservice.yaml`, `appointmentservice.yaml`, `userstatisticsservice.yaml`.
- `services/*.yaml` — OpenAPI specs of **downstream services** for generated clients: `agencyservice`, `agencyadminservice`, `applicationsettingsservice`, `appointmentService`, `consultingtypeservice`, `keycloakextension`, `mailservice`, `statisticsservice`, `tenantservice`, `tenantadminservice`, `topicservice`. (The former `liveservice.yaml` and `messageservice.yaml` clients are gone.)
- `src/main/resources/application.properties` plus profiles `-dev`, `-prod`, `-staging`, `-testing` (H2); `logback-spring.xml`, `messages.properties`, `email/layout/` (branded email templates), `monitoring/`, `replica-safety-components.json`.

### Data And Schema

- `src/main/resources/db/changelog/userservice-master.xml` — a **single master changelog for all environments** (part of the Liquibase re-enablement plan).
- `src/main/resources/db/changelog/changeset/` — 89 changeset directories, `0001_initsql` through `0085_consultant_internal_display_name`. Recent ones track current features: `0076_account_invite_reservation_token`, `0078_handshake`, `0079_support_room`, `0081_account_invite_provisioning`, `0082_scheduled_task_claim`, `0083_consultant_personal_info`, `0084_event_notification_explanation_backfill`.
- JPA entities live in `api/model/` (User, Consultant, Session, Chat, Admin, …).

### Deployment And Operations

- `Dockerfile` (Temurin 21 JRE), `run-trivy.sh` for image scanning.
- `.github/workflows/`: `ci-feature-branch.yml`, `ci-pull-request.yml`, `ci-main.yml`, `mariadb-contract.yml`, `openapi-contracts.yml`, `release-image.yml`; reusable steps in `.github/actions/maven-build` and `.github/actions/docker-build-push`.
- Root `tests/` holds non-unit suites: `tests/ci`, `tests/contracts`, `tests/load`.

### Documentation

- `README.md` / `readme.md` — current purpose statement (Matrix room lifecycle, no Rocket.Chat).
- `docs/` — operational contracts: `api-error-contract.md`, `live-chat-observability.md`, `replica-safety.md`, `schema-migrations.md`.
- `documentation/` — ADRs and deep dives: `ADR-SECURITY-02-unified-crypto-boundary.md`, `MATRIX_SYNC_OBSERVABILITY.md`, `USER_SERVICE_REPLICA_SAFETY.md`, `USER_SERVICE_STABILITY.md`, replica-safety notes per workflow, `local-development.md`, plus historical flowcharts (some still labelled Rocket.Chat — historical only).
- `INVITE_LINKS_API.md`, `CHANGELOG.md`.

## Major Flows

- Boot: `UserServiceApplication` → Spring Security resource-server config (`config/auth/`) → Liquibase master changelog → scheduled workflows registered via `workflow/scheduling`.
- Registration/enquiry: `UserRegistrationControllerDelegate` → `CreateUserFacade` / `CreateSessionFacade` → `CreateEnquiryMessageFacade` → `Messenger` → `MatrixSessionRoomGateway` (encrypted Matrix room per session).
- Session assignment: `facade/assignsession` → `MatrixSessionAssignmentGateway` joins/removes consultants in the session's Matrix room (see PR #1074: late-joining team counsellors are joined into existing enquiry rooms).
- Session lists: `UserSessionControllerDelegate` / `ConversationController` → `facade/sessionlist` → repositories.
- Account lifecycle: `workflow/deactivate`, `workflow/delete`, `actions/user/DeactivateKeycloakUserActionCommand` → Keycloak adapter.

## API And Service Dependencies

Implements the five `api/*.yaml` specs; calls AgencyService, ConsultingTypeService, TenantService/TenantAdminService, TopicService, MailService, StatisticsService, AppointmentService, ApplicationSettingsService and the Keycloak extension via generated clients from `services/*.yaml`. Talks directly to Matrix Synapse (`adapters/matrix`), Keycloak (`adapters/keycloak`), MariaDB, Redis, RabbitMQ, and Firebase.

## Authentication Relationship

JWT bearer tokens validated as an OAuth2 resource server against Keycloak (`keycloak.auth-server-url`, `keycloak.realm`, bearer-only). Authorities and role config in `config/auth/` (`Authority.java`, `IdentityConfig.java`); admin-side user management through `keycloak-admin-client` 26. 2FA/OTP flows go through the Keycloak extension (`services/keycloakextension.yaml`) and `UserTwoFactorAuthControllerDelegate`.

## ORISO Ecosystem Fit

`ORISO-UserService` is the core backend microservice of the ORISO online-counselling platform. The graph focuses only on this repo's files and records cross-cutting evidence such as API, auth, database, and deployment files when those relationships are visible locally.
