# ORISO Ecosystem Connections

## Navigation

- [Role In The Platform](#role-in-the-platform)
- [Inbound Consumers](#inbound-consumers)
- [Outbound Services](#outbound-services)
- [Shared Runtime Infrastructure](#shared-runtime-infrastructure)
- [Data Ownership](#data-ownership)

## Role In The Platform

UserService is the central user/session lifecycle service in the ORISO ecosystem. It owns asker and consultant accounts in its database, coordinates identity state in Keycloak, provisions chat state in Rocket.Chat and Matrix, and exposes APIs consumed by frontend/admin clients and other backend services.

## Inbound Consumers

- ORISO-Frontend uses `/users/**`, `/conversations/**`, `/matrix/**`, appointment, notification, draft, and session endpoints for registration, counselling, chat, profile, and messaging flows.
- ORISO-Admin uses `/useradmin/**`, consultant/admin/search/report endpoints, invite links, audit logs, and deletion-pause operations.
- Technical backend callers use endpoints such as `/users/notifications`, `/users/messages/key`, imports, statistics, and lifecycle operations guarded by technical roles.

## Outbound Services

The `services/*.yaml` contracts and `config/apiclient/*` factories connect UserService to:

- Keycloak and keycloak-extension for authentication, roles, password, 2FA, and user administration.
- Rocket.Chat for group/user membership, E2E keys, room state, and legacy chat lifecycle.
- Matrix Synapse for Matrix user/room/message/media functionality.
- Agency service and agency admin service for agency metadata and agency administration.
- Tenant service and tenant admin service for tenant lookup and tenant-admin flows.
- Consulting type, topic, and application settings services for registration rules and feature content.
- Appointment service for booking-backed enquiries and consultant/agency sync.
- Message service for message streams, keys, and drafts.
- Mail service and SMTP for user/admin/system notifications.
- Live service for live event dispatch.
- Statistics service and RabbitMQ for event reporting.

## Shared Runtime Infrastructure

Runtime settings in `application.properties` expect externally injected URLs and credentials for MariaDB, Keycloak, Rocket.Chat, Matrix, RabbitMQ, Redis, SMTP, Firebase, and peer ORISO APIs. Docker packaging is local to this repository, while Kubernetes deployment appears to be managed from the surrounding ORISO workspace.

## Data Ownership

UserService owns its MariaDB tables for users, consultants, sessions, chats, admin relations, mobile tokens, invite links, notifications, deletion lifecycle, tombstones, and workflow audit logs. It does not own Keycloak, Rocket.Chat, Matrix, agency, tenant, appointment, message, mail, live, or statistics data; it coordinates those systems through adapters and generated clients.

