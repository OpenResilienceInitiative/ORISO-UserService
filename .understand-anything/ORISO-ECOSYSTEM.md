# ORISO Ecosystem Notes: ORISO-UserService

This graph was generated for `ORISO-UserService` only. It does not analyze sibling repositories.

## Local Role Evidence

- Purpose: The UserService is the core backend microservice — it owns user and consultant accounts, enquiries, counselling sessions, session lists, and the application-side lifecycle of encrypted Matrix rooms (registration, enquiry creation, consultant assignment, group chats, anonymous counselling, account deactivation/deletion workflows).
- Languages: dockerfile, java, javascript, json, markdown, properties, python, shell, sql, xml, yaml
- Frameworks/tools: Docker, Spring Boot 4.0.7 (Java 21), Spring Security OAuth2 resource server, Spring Data JPA + Liquibase (MariaDB), Redis, RabbitMQ, OpenAPI Generator
- Implemented APIs (`api/`): 5 OpenAPI specs
- Downstream client specs (`services/`): 11 OpenAPI specs
- Auth: Keycloak 26 (resource server + admin client, Keycloak extension for OTP/2FA)
- Messaging: Matrix Synapse adapter (`adapters/matrix`), MatrixRTC call policy — no Rocket.Chat code remains
- Deployment: Dockerfile (Temurin 21 JRE) + 6 GitHub Actions workflows incl. MariaDB and OpenAPI contract gates

## Integration Clues

Specs this service implements (server-side):

- `api/userservice.yaml` (main API), `api/useradminservice.yaml`, `api/conversationservice.yaml`, `api/appointmentservice.yaml`, `api/userstatisticsservice.yaml`
- `INVITE_LINKS_API.md` (docs, markdown)

Downstream services called via generated clients:

- `services/agencyservice.yaml`, `services/agencyadminservice.yaml`
- `services/consultingtypeservice.yaml`, `services/topicservice.yaml`
- `services/tenantservice.yaml`, `services/tenantadminservice.yaml`
- `services/mailservice.yaml`, `services/statisticsservice.yaml`
- `services/appointmentService.yaml`, `services/applicationsettingsservice.yaml`
- `services/keycloakextension.yaml` (OTP/2FA)

Direct infrastructure integrations (no OpenAPI spec):

- Matrix Synapse: `src/main/java/de/caritas/cob/userservice/api/adapters/matrix/` (`MatrixRoomClient`, `MatrixMediaClient`, `MatrixSynapseService`, session room/assignment gateways; `matrix.apiUrl` config)
- Keycloak: `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/` (`KeycloakClient`, `KeycloakService`, config, DTOs)
- MariaDB (Liquibase master changelog), Redis (caching, live availability, one-time tokens), RabbitMQ (`statistics.topic` exchange), Firebase Admin (mobile push)
- `documentation/ADR-SECURITY-02-unified-crypto-boundary.md` (crypto boundary ADR)
