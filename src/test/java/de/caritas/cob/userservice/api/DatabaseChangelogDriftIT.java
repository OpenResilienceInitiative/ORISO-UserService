package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Permanent schema drift guard (Liquibase Re-Enablement Plan 2026-07-04, package L1).
 *
 * <p>Contract: point {@code LIQUIBASE_IT_DB_URL} at an EMPTY MariaDB database that is named {@code
 * userservice} — the base schema file ({@code 0001_initsql/initTables.sql}) hardcodes the {@code
 * userservice.} schema prefix, so other database names fail. Liquibase then applies the single
 * master changelog ({@code db/changelog/userservice-master.xml}) from scratch and Hibernate
 * validates the resulting schema against ALL mapped JPA entities ({@code ddl-auto=validate}). If a
 * schema change is introduced through an entity without a matching changeset (or with an
 * incompatible type), the application context fails to start and this test goes red.
 *
 * <p>Run locally, e.g.:
 *
 * <pre>
 *   docker run -d --name drift-mariadb -p 3312:3306 -e MARIADB_ROOT_PASSWORD=root \
 *     -e MARIADB_DATABASE=userservice mariadb:11.0.6
 *   LIQUIBASE_IT_DB_URL=jdbc:mariadb://127.0.0.1:3312/userservice \
 *     ./mvnw test -Dtest=DatabaseChangelogDriftIT -DfailIfNoTests=true
 * </pre>
 *
 * <p>Optional: {@code LIQUIBASE_IT_DB_USERNAME} / {@code LIQUIBASE_IT_DB_PASSWORD} (default {@code
 * root} / {@code root}).
 *
 * <p>Deliberately does NOT use the {@code testing} Spring profile — that profile is H2/create-drop
 * based and would bypass both Liquibase and MariaDB semantics. (The project's Testcontainers 1.17.6
 * dependency predates current Docker Desktop API versions and cannot bootstrap containers, hence
 * the env-var-provided database.)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class DatabaseChangelogDriftIT {

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    // exercise the widest context set (dev incl. seed/demo changesets)
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Autowired private EntityManager entityManager;

  @Test
  void freshDatabaseBuiltFromChangelogShouldSatisfyJpaEntityModel() {
    // Reaching this point means: Liquibase applied userservice-master.xml AND Hibernate
    // schema validation passed for every mapped entity.
    assertThat(entityManager).isNotNull();
    Number changesets =
        (Number)
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG")
                .getSingleResult();
    // 59 changesets applied as of package L1 (2026-07-04); only ever grows.
    assertThat(changesets.longValue())
        .as("number of applied Liquibase changesets")
        .isGreaterThanOrEqualTo(59);
  }
}
