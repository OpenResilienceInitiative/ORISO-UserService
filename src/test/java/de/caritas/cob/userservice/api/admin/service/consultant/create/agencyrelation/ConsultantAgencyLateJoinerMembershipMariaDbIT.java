package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the same repository and admin entry points on the Liquibase-managed MariaDB schema. */
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class ConsultantAgencyLateJoinerMembershipMariaDbIT extends ConsultantAgencyLateJoinerMembershipIT {
  @Autowired private DataSource dataSource;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
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
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
  }

  @BeforeEach
  void usesMariaDb() throws SQLException {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MariaDB");
    }
  }
}
