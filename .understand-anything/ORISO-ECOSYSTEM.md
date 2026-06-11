# ORISO Ecosystem Notes: ORISO-UserService

This graph was generated for `ORISO-UserService` only. It does not analyze sibling repositories.

## Local Role Evidence

- Purpose: The UserService provides different functionalities from creating and updating user accounts and their sessions, providing session lists up to creating and editing Rocket.Chat groups.
- Languages: dockerfile, java, javascript, json, markdown, properties, python, shell, sql, xml, yaml
- Frameworks/tools: Docker, Spring Boot
- API/service-related files: 12
- Auth-related files: 12
- Database-related files: 12
- Deployment-related files: 4

## Integration Clues

- `api/appointmentservice.yaml` (config, yaml)
- `api/conversationservice.yaml` (config, yaml)
- `api/useradminservice.yaml` (config, yaml)
- `api/userservice.yaml` (config, yaml)
- `api/userstatisticsservice.yaml` (config, yaml)
- `INVITE_LINKS_API.md` (docs, markdown)
- `services/agencyadminservice.yaml` (config, yaml)
- `services/agencyservice.yaml` (config, yaml)
- `services/applicationsettingsservice.yaml` (config, yaml)
- `services/appointmentService.yaml` (config, yaml)
- `services/consultingtypeservice.yaml` (config, yaml)
- `services/keycloakextension.yaml` (config, yaml)
- `documentation/ADR-SECURITY-02-unified-crypto-boundary.md` (docs, markdown)
- `services/keycloakextension.yaml` (config, yaml)
- `src/main/java/de/caritas/cob/userservice/api/actions/user/DeactivateKeycloakUserActionCommand.java` (code, java)
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/config/KeycloakConfig.java` (code, java)
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/config/KeycloakCustomConfig.java` (code, java)
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/dto/KeycloakCreateUserResponseDTO.java` (code, java)
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/dto/KeycloakLoginResponseDTO.java` (code, java)
- `src/main/java/de/caritas/cob/userservice/api/adapters/keycloak/KeycloakClient.java` (code, java)
