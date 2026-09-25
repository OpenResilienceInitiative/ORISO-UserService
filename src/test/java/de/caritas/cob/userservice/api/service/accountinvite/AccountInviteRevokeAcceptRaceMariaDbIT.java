package de.caritas.cob.userservice.api.service.accountinvite;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link RevokeAcceptRaceContract} on real MariaDB and the Liquibase schema, where InnoDB's row
 * locks decide the race. Run by the mariadb-contract job:
 *
 * <pre>
 *   docker run -d --name revoke-race-mariadb -p 3337:3306 -e MARIADB_ROOT_PASSWORD=root \
 *     -e MARIADB_DATABASE=userservice mariadb:10.11
 *   LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3337/userservice \
 *     ./mvnw -B -Dskip.unit-tests=true -Dtest=AccountInviteRevokeAcceptRaceMariaDbIT integration-test
 * </pre>
 */
@TestPropertySource(properties = "spring.profiles.active=testing")
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class AccountInviteRevokeAcceptRaceMariaDbIT extends RevokeAcceptRaceContract {

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    // The testing profile seeds H2 by script after Hibernate; Liquibase owns this schema.
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }
}
