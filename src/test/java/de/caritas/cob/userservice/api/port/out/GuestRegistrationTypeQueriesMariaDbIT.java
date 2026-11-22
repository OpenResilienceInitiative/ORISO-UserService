package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link GuestRegistrationTypeQueriesContract} on the real MariaDB schema built by Liquibase. Runs
 * in the MariaDB contract job ({@code LIQUIBASE_IT_DB_URL}); each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import(JpaAuditingConfiguration.class)
class GuestRegistrationTypeQueriesMariaDbIT extends GuestRegistrationTypeQueriesContract {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    r.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    r.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    r.add("spring.liquibase.enabled", () -> "true");
    r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    r.add("spring.liquibase.contexts", () -> "dev,seed");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    r.add("multitenancy.enabled", () -> "false");
  }
}
