# ORISO UserService

## Overview
Spring Boot service for managing users, consultants, and askers in the Online Beratung platform.

## Quick Start

### Run in Kubernetes
The service automatically starts via Kubernetes deployment using Maven Spring Boot plugin.

```bash
# Check service status
kubectl get pods -n caritas | grep userservice
kubectl logs -n caritas -l app=userservice --tail=100
```

### Run Locally (Development)
```bash
cd /home/caritas/Desktop/online-beratung/caritas-workspace/ORISO-UserService
chmod +x mvnw
./mvnw spring-boot:run -Dspring-boot.run.profiles=local -DskipTests
```

## Configuration

### Database Connection
**MariaDB ClusterIP:** `10.43.123.72:3306`

```properties
# application-local.properties
spring.datasource.url=jdbc:mariadb://10.43.123.72:3306/userservice
spring.datasource.username=userservice
spring.datasource.password=userservice
```

### Liquibase
**STATUS:** ⚠️ **DISABLED**

```properties
spring.liquibase.enabled=false
```

Database schemas are managed separately in `ORISO-Database` repository.

### Keycloak
```properties
keycloak.auth-server-url=http://localhost:8080
keycloak.realm=online-beratung
keycloak.resource=user-service
```

### RabbitMQ
```properties
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.username=user
spring.rabbitmq.password=password
```

## Important Notes
- **Port:** `8082`
- **Profile:** `local`
- **Liquibase:** DISABLED - schemas managed in ORISO-Database
- **Database:** Uses MariaDB ClusterIP (NOT localhost)
- **Host Network:** Enabled in Kubernetes for direct localhost access
- **Inter-service Communication:** Uses localhost URLs for other services

## Kubernetes Deployment Path
```
/home/caritas/Desktop/online-beratung/caritas-workspace/ORISO-UserService
```

## Health Check
```bash
curl http://localhost:8082/actuator/health
```

## Dependencies
- Java 17
- Spring Boot 2.7.14
- MariaDB
- RabbitMQ
- Keycloak

