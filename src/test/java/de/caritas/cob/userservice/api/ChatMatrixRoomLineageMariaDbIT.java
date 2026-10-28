package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual MariaDB migration/rollback plus opaque case-sensitive Matrix room identity. */
@Testcontainers
class ChatMatrixRoomLineageMariaDbIT extends ChatMatrixRoomLineageMigrationTest {
  @Container
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  @Override
  protected Connection database() throws Exception {
    var connection =
        DriverManager.getConnection(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    for (var table :
        java.util.List.of(
            "chat_matrix_room", "chat", "consultant", "DATABASECHANGELOG", "DATABASECHANGELOGLOCK"))
      execute(connection, "DROP TABLE IF EXISTS " + table);
    execute(
        connection,
        "CREATE TABLE consultant (consultant_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT)");
    execute(
        connection,
        "CREATE TABLE chat (id BIGINT UNSIGNED PRIMARY KEY,matrix_room_id VARCHAR(512),current_occurrence_index INT,consultant_id_owner VARCHAR(36)) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
    return connection;
  }

  @Test
  void roomIdsRemainOpaqueAndCaseSensitiveDespiteLegacyChatCollation() throws Exception {
    try (var connection = database();
        var migration = migration(connection)) {
      execute(connection, "INSERT INTO consultant VALUES ('owner',41)");
      execute(
          connection,
          "INSERT INTO chat VALUES (1,'!Case:synthetic.test',0,'owner'),(2,'!case:synthetic.test',0,'owner')");
      migration.update(new Contexts(), new LabelExpression());
      assertThat(count(connection, "chat_matrix_room")).isEqualTo(2);
      try (var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  "SELECT series_id FROM chat_matrix_room WHERE room_id='!Case:synthetic.test'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getLong(1)).isEqualTo(1);
        assertThat(result.next()).isFalse();
      }
    }
  }
}
