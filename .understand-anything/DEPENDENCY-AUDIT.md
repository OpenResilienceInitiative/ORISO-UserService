# ORISO UserService Dependency Notes

## Navigation

- [Build Systems](#build-systems)
- [Runtime Dependency Families](#runtime-dependency-families)
- [Generated Clients](#generated-clients)
- [Static Risk Notes](#static-risk-notes)
- [Suggested Verification](#suggested-verification)

## Build Systems

- Maven is the production build system through `pom.xml`.
- Node/npm is present for release tooling only through `package.json` and `package-lock.json`.

## Runtime Dependency Families

- Spring Boot web/security/JPA/cache/Redis/AOP/HATEOAS/AMQP/actuator.
- Keycloak Spring adapter, Spring Boot starter, and admin client.
- OpenAPI Generator and generated Spring/Java clients.
- MariaDB JDBC, Liquibase, Hibernate Search.
- Rocket.Chat REST and MongoDB access.
- Matrix Synapse REST access.
- RabbitMQ, Redis, Firebase Admin, mail/SMTP, statistics publishing.
- Logging through SLF4J/Logback plus Log4j bridges and logstash encoder.

## Generated Clients

`pom.xml` generates inbound API interfaces from `api/*.yaml` and outbound Java clients from `services/*.yaml`. Generated sources are written under `target/generated-sources` and should not be edited directly.

## Static Risk Notes

This file does not replace SCA output. It flags dependency areas to verify:

- Spring Boot 2.7.x / Spring Security 5.x upgrade path.
- Legacy Keycloak adapters.
- Springfox 2.x.
- PowerMock and mixed JUnit-era test support.
- Old test/runtime utility versions: H2 1.4.200, jsoup 1.14.3, firebase-admin 8.1.0, commons-csv 1.8, json-unit 2.28.0.
- Forced Log4j 2.17.1 pin should be checked against the current approved baseline.

## Suggested Verification

```bash
./mvnw -DskipTests dependency:tree
./mvnw test
npm audit
```

For production release, run the organization-approved Maven dependency vulnerability scanner and store the report next to the deployment evidence.

