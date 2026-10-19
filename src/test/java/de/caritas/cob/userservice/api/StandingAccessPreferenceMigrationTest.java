package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class StandingAccessPreferenceMigrationTest {
  @Test
  void oldAndNewSessionsDefaultOffAndRollbackPreservesSessionIdentity() throws Exception {
    try (var connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:standing-access-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE session (id BIGINT PRIMARY KEY)");
        statement.execute("INSERT INTO session(id) VALUES (123)");
      }
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var migration =
          new Liquibase(
              "db/changelog/changeset/20261007_standing_access_preference_1657/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              database)) {
        migration.update(new Contexts(), new LabelExpression());
        try (var statement = connection.createStatement()) {
          statement.execute("INSERT INTO session(id) VALUES (456)");
          try (var rows =
              statement.executeQuery(
                  "SELECT always_ask_before_additional_access FROM session ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isFalse();
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBoolean(1)).isFalse();
          }
          statement.execute(
              "UPDATE session SET always_ask_before_additional_access = TRUE WHERE id = 123");
        }
        connection.commit();
        migration.rollback(1, new Contexts(), new LabelExpression());
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT id FROM session ORDER BY id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(123);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(456);
        }
      }
    }
  }
}
