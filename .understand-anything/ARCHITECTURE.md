# Architecture Notes: ORISO-UserService

## Purpose

The UserService provides different functionalities from creating and updating user accounts and their sessions, providing session lists up to creating and editing Rocket.Chat groups.

## Architecture Layers

### Api And Routing

HTTP routes, controllers, API clients, and request boundaries.

Key files:
- `src/main/java/de/caritas/cob/userservice/api/AccountManager.java` - src/main/java/de/caritas/cob/userservice/api/AccountManager.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/IdentityManager.java` - src/main/java/de/caritas/cob/userservice/api/IdentityManager.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/Messenger.java` - src/main/java/de/caritas/cob/userservice/api/Messenger.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/Organizer.java` - src/main/java/de/caritas/cob/userservice/api/Organizer.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/PatchConsultantSaga.java` - src/main/java/de/caritas/cob/userservice/api/PatchConsultantSaga.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/PatchConsultantSagaRollbackHandler.java` - src/main/java/de/caritas/cob/userservice/api/PatchConsultantSagaRollbackHandler.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java` - src/main/java/de/caritas/cob/userservice/api/UserServiceApplication.java is a code file under src; starts with "@SpringBootApplication(exclude = MongoAutoConfiguration.class, MongoDataAutoConfiguration.class)".
- `src/main/java/de/caritas/cob/userservice/api/UserServiceMapper.java` - src/main/java/de/caritas/cob/userservice/api/UserServiceMapper.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/actions/ActionCommand.java` - src/main/java/de/caritas/cob/userservice/api/actions/ActionCommand.java is a code file under src; starts with "public interface ActionCommandT".
- `src/main/java/de/caritas/cob/userservice/api/actions/chat/ChatReCreator.java` - src/main/java/de/caritas/cob/userservice/api/actions/chat/ChatReCreator.java is a code file under src in this repository.
- `src/main/java/de/caritas/cob/userservice/api/actions/chat/StopChatActionCommand.java` - src/main/java/de/caritas/cob/userservice/api/actions/chat/StopChatActionCommand.java is a code file under src; starts with "/** Action to perform all necessary steps to stop an active group chat. */".
- `src/main/java/de/caritas/cob/userservice/api/actions/registry/ActionContainer.java` - src/main/java/de/caritas/cob/userservice/api/actions/registry/ActionContainer.java is a code file under src; starts with "* Container class to collect fluently actions to peform.".

### Application Core

Core application code and shared utilities.

Key files:
- `.gitignore` - .gitignore is a code file under repository root in this repository.
- `.swagger-codegen-ignore` - .swagger-codegen-ignore is a code file under repository root; starts with "# Swagger Codegen Ignore".
- `LICENSE` - LICENSE is a code file under repository root; starts with "GNU AFFERO GENERAL PUBLIC LICENSE".
- `check-version.sh` - check-version.sh is a script file under repository root in this repository.
- `commitlint.config.js` - commitlint.config.js is a code file under repository root; starts with "module.exports =  extends: '@commitlint/config-conventional' ;".
- `deploy-development.sh` - deploy-development.sh is a script file under repository root in this repository.
- `mvnw` - mvnw is a code file under repository root in this repository.
- `run-trivy.sh` - run-trivy.sh is a script file under repository root; starts with "rm report*.sarif".

### Configuration

Runtime, build, package, framework, and environment configuration.

Key files:
- `.github/actions/docker-build-push/action.yml` - .github/actions/docker-build-push/action.yml is a config file under .github; starts with "name: Reusable Docker Build and Publish steps".
- `.github/actions/maven-build/action.yml` - .github/actions/maven-build/action.yml is a config file under .github; starts with "name: Reusable Maven Build steps".
- `.mvn/wrapper/maven-wrapper.properties` - .mvn/wrapper/maven-wrapper.properties is a config file under .mvn; starts with "wrapperVersion=3.3.4".
- `api/appointmentservice.yaml` - api/appointmentservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/conversationservice.yaml` - api/conversationservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/useradminservice.yaml` - api/useradminservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/userservice.yaml` - api/userservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `api/userstatisticsservice.yaml` - api/userstatisticsservice.yaml is a config file under api; starts with "openapi: 3.0.1".
- `package-lock.json` - package-lock.json is a config file under repository root; starts with ""name": "online-beratung-userservice",".
- `package.json` - package.json is a config file under repository root; starts with ""name": "online-beratung-userservice",".
- `pom.xml` - pom.xml is a config file under repository root; starts with "?xml version="1.0" encoding="UTF-8"?".
- `services/agencyadminservice.yaml` - services/agencyadminservice.yaml is a config file under services; starts with "openapi: 3.0.1".

### Data And Schema

Database schema, migrations, GraphQL/protobuf/schema files, and seed data.

Key files:
- `src/main/java/de/caritas/cob/userservice/api/config/migration/TemporaryPublicPrivateKeysTask.java` - src/main/java/de/caritas/cob/userservice/api/config/migration/TemporaryPublicPrivateKeysTask.java is a data file under src in this repository.
- `src/main/resources/migration/KeycloakRoleNameMigration.py` - src/main/resources/migration/KeycloakRoleNameMigration.py is a data file under src; starts with "server = 'https://DOMAIN_NAME'".
- `src/main/resources/db/changelog/changeset/0001_initsql/initTables.sql` - src/main/resources/db/changelog/changeset/0001_initsql/initTables.sql is a data file under src; starts with "CREATE TABLE userservice.user (".
- `src/main/resources/db/changelog/changeset/0001_initsql/initTrigger.sql` - src/main/resources/db/changelog/changeset/0001_initsql/initTrigger.sql is a data file under src; starts with "CREATE TRIGGER userservice.user_update BEFORE UPDATE ON userservice.user FOR EACH ROW BEGIN".
- `src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/feedbackChatColumn-rollback.sql` - src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/feedbackChatColumn-rollback.sql is a data file under src; starts with "ALTER TABLE userservice.session".
- `src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/feedbackChatColumn.sql` - src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/feedbackChatColumn.sql is a data file under src; starts with "ALTER TABLE userservice.session".
- `src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/monitoringKeys-rollback.sql` - src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/monitoringKeys-rollback.sql is a data file under src; starts with "ALTER TABLE userservice.session_monitoring".
- `src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/monitoringKeys.sql` - src/main/resources/db/changelog/changeset/0002_monitoringKeys_feedbackChatClumn/monitoringKeys.sql is a data file under src; starts with "ALTER TABLE userservice.session_monitoring".
- `src/main/resources/db/changelog/changeset/0003_user_attribute_languageFormal/userLanguageFormalColumn-rollback.sql` - src/main/resources/db/changelog/changeset/0003_user_attribute_languageFormal/userLanguageFormalColumn-rollback.sql is a data file under src; starts with "ALTER TABLE userservice.user".
- `src/main/resources/db/changelog/changeset/0003_user_attribute_languageFormal/userLanguageFormalColumn.sql` - src/main/resources/db/changelog/changeset/0003_user_attribute_languageFormal/userLanguageFormalColumn.sql is a data file under src; starts with "ALTER TABLE userservice.user".
- `src/main/resources/db/changelog/changeset/0004_consultant_attribute_languageFormal/consultantLanguageFormalColumn-rollback.sql` - src/main/resources/db/changelog/changeset/0004_consultant_attribute_languageFormal/consultantLanguageFormalColumn-rollback.sql is a data file under src; starts with "ALTER TABLE userservice.consultant".
- `src/main/resources/db/changelog/changeset/0004_consultant_attribute_languageFormal/consultantLanguageFormalColumn.sql` - src/main/resources/db/changelog/changeset/0004_consultant_attribute_languageFormal/consultantLanguageFormalColumn.sql is a data file under src; starts with "ALTER TABLE userservice.consultant".

### Deployment And Operations

Docker, Kubernetes, CI/CD, infrastructure, and operational resources.

Key files:
- `.github/workflows/ci-feature-branch.yml` - .github/workflows/ci-feature-branch.yml is a pipeline file under .github; starts with "name: CI - Feature Branch".
- `.github/workflows/ci-main.yml` - .github/workflows/ci-main.yml is a pipeline file under .github; starts with "name: CI - Main".
- `.github/workflows/ci-pull-request.yml` - .github/workflows/ci-pull-request.yml is a pipeline file under .github; starts with "name: CI - Pull Request".
- `Dockerfile` - Dockerfile is a infra file under repository root; starts with "FROM eclipse-temurin:17-jre".

### Documentation

Human-facing documentation and project notes.

Key files:
- `CHANGELOG.md` - CHANGELOG.md is a docs file under repository root in this repository.
- `INVITE_LINKS_API.md` - INVITE_LINKS_API.md is a docs file under repository root; starts with "# Invite Links API — for the Frontend Developer".
- `README.md` - README.md is a docs file under repository root; starts with "# Online-Beratung UserService".
- `documentation/ADR-SECURITY-02-unified-crypto-boundary.md` - documentation/ADR-SECURITY-02-unified-crypto-boundary.md is a docs file under documentation; starts with "# ADR Security-02: Unified Cryptographic Boundary".
- `readme.md` - readme.md is a docs file under repository root; starts with "# Online-Beratung UserService".

## Major Flows

- Entry and boot flow: `Dockerfile`, `src/main/java/de/caritas/cob/userservice/api/AccountManager.java`, `src/main/java/de/caritas/cob/userservice/api/actions/ActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/chat/ChatReCreator.java`, `src/main/java/de/caritas/cob/userservice/api/actions/chat/StopChatActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/registry/ActionContainer.java`, `src/main/java/de/caritas/cob/userservice/api/actions/registry/ActionsRegistry.java`, `src/main/java/de/caritas/cob/userservice/api/actions/session/DeactivateSessionActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/session/PostConversationFinishedAliasMessageActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/session/SendFinishedAnonymousConversationEventActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/session/SetRocketChatRoomReadOnlyActionCommand.java`, `src/main/java/de/caritas/cob/userservice/api/actions/user/DeactivateKeycloakUserActionCommand.java`
- API/service flow: `api/appointmentservice.yaml`, `api/conversationservice.yaml`, `api/useradminservice.yaml`, `api/userservice.yaml`, `api/userstatisticsservice.yaml`, `INVITE_LINKS_API.md`, `services/agencyadminservice.yaml`, `services/agencyservice.yaml`, `services/applicationsettingsservice.yaml`, `services/appointmentService.yaml`, `services/consultingtypeservice.yaml`, `services/keycloakextension.yaml`
- Configuration flow: `.github/actions/docker-build-push/action.yml`, `.github/actions/maven-build/action.yml`, `.mvn/wrapper/maven-wrapper.properties`, `api/appointmentservice.yaml`, `api/conversationservice.yaml`, `api/useradminservice.yaml`, `api/userservice.yaml`, `api/userstatisticsservice.yaml`

## API And Service Dependencies

- `api/appointmentservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `api/conversationservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `api/useradminservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `api/userservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `api/userstatisticsservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `INVITE_LINKS_API.md` contributes API, service, route, client, or service-boundary behavior.
- `services/agencyadminservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `services/agencyservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `services/applicationsettingsservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `services/appointmentService.yaml` contributes API, service, route, client, or service-boundary behavior.
- `services/consultingtypeservice.yaml` contributes API, service, route, client, or service-boundary behavior.
- `services/keycloakextension.yaml` contributes API, service, route, client, or service-boundary behavior.

## Authentication Relationship

- `documentation/ADR-SECURITY-02-unified-crypto-boundary.md` is auth/security-related by filename or path.
- `services/keycloakextension.yaml` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/actions/user/DeactivateKeycloakUserActionCommand.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/config/KeycloakConfig.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/config/KeycloakCustomConfig.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/dto/KeycloakCreateUserResponseDTO.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/dto/KeycloakLoginResponseDTO.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/KeycloakClient.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/KeycloakMapper.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/KeycloakService.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/config/auth/Authority.java` is auth/security-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/config/auth/IdentityConfig.java` is auth/security-related by filename or path.

## Database Relationship

- `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/interceptor/ApiDefaultResponseEntityExceptionHandler.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/adapters/web/controller/interceptor/ApiResponseEntityExceptionHandler.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/admin/report/model/AgencyDependedViolationReportRule.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/admin/report/model/ViolationReportRule.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/config/auth/IdentityConfig.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/config/migration/TemporaryPublicPrivateKeysTask.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/conversation/model/AnonymousUserCredentials.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/conversation/model/ConversationListType.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/conversation/model/PageableListRequest.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/IdentityManager.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/model/Admin.java` is database, schema, repository, entity, model, or migration-related by filename or path.
- `src/main/java/de/caritas/cob/userservice/api/model/AdminAgency.java` is database, schema, repository, entity, model, or migration-related by filename or path.

## Deployment Relationship

- `.github/workflows/ci-feature-branch.yml` participates in deployment, infrastructure, or CI/CD.
- `.github/workflows/ci-main.yml` participates in deployment, infrastructure, or CI/CD.
- `.github/workflows/ci-pull-request.yml` participates in deployment, infrastructure, or CI/CD.
- `Dockerfile` participates in deployment, infrastructure, or CI/CD.

## ORISO Ecosystem Fit

`ORISO-UserService` is one repository in the ORISO system. The graph focuses only on this repo's files and records cross-cutting evidence such as API, auth, database, and deployment files when those relationships are visible locally.
