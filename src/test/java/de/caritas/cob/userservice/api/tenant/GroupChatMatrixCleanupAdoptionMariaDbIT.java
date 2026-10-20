package de.caritas.cob.userservice.api.tenant;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same actual-repository concurrent adoption contract on MariaDB and full Liquibase schema. */
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class GroupChatMatrixCleanupAdoptionMariaDbIT extends GroupChatMatrixCleanupAdoptionIT {
  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
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
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @Override
  protected void assertObservedRetryState(java.util.List<String> observed) {
    org.assertj.core.api.Assertions.assertThat(observed).containsExactly("OWNER_LOCK");
  }

  @Override
  protected String blockedRetryStateQuery() {
    // MariaDB may wait in query optimization (PROCESSLIST Statistics) before
    // INNODB_TRX exposes LOCK WAIT; observe the actual pending owner-lock SQL.
    return "SELECT 'OWNER_LOCK' FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'";
  }
}
