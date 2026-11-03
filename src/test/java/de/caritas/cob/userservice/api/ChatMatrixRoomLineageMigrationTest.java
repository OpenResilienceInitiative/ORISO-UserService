package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * Runs the actual migration against SQL; duplicate room mappings never become trusted provenance.
 */
class ChatMatrixRoomLineageMigrationTest {
  @Test
  void backfillUsesOnlyUniqueCurrentMappingsAndPreservesTheirActualOccurrence() throws Exception {
    try (var connection = database();
        var migration = migration(connection)) {
      execute(connection, "INSERT INTO consultant VALUES ('owner',41)");
      execute(
          connection,
          "INSERT INTO chat VALUES (1,'!first:synthetic.test',0,'owner'),(2,'!later:synthetic.test',7,'owner'),(3,'!ambiguous:synthetic.test',0,'owner'),(4,'!ambiguous:synthetic.test',1,'owner')");
      migration.update(new Contexts(), new LabelExpression());
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT room_id,series_id,occurrence_index,owner_tenant_id FROM chat_matrix_room ORDER BY series_id")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("!first:synthetic.test");
        assertThat(rows.getLong(2)).isEqualTo(1);
        assertThat(rows.getInt(3)).isZero();
        assertThat(rows.getLong(4)).isEqualTo(41);
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("!later:synthetic.test");
        assertThat(rows.getInt(3)).isEqualTo(7);
        assertThat(rows.next()).isFalse();
      }
      assertThat(count(connection, "chat")).isEqualTo(4);
    }
  }

  @Test
  void constraintsPreventTwoRoomOwnersAndDeleteOnlyTheDeletedSeriesProvenance() throws Exception {
    try (var connection = database();
        var migration = migration(connection)) {
      execute(connection, "INSERT INTO consultant VALUES ('owner',41)");
      execute(
          connection,
          "INSERT INTO chat VALUES (1,'!first:synthetic.test',0,'owner'),(2,'!other:synthetic.test',0,'owner')");
      migration.update(new Contexts(), new LabelExpression());
      assertThatThrownBy(
              () ->
                  execute(
                      connection,
                      "INSERT INTO chat_matrix_room VALUES ('!first:synthetic.test',2,1,41)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  execute(
                      connection,
                      "INSERT INTO chat_matrix_room VALUES ('!duplicate-occurrence:synthetic.test',1,0,41)"))
          .isInstanceOf(SQLException.class);
      execute(connection, "DELETE FROM chat WHERE id=1");
      assertThat(count(connection, "chat_matrix_room")).isEqualTo(1);
      assertThat(count(connection, "chat")).isEqualTo(1);
    }
  }

  @Test
  void rollbackPreservesTheExistingSeriesAndTheirCanonicalRooms() throws Exception {
    try (var connection = database();
        var migration = migration(connection)) {
      execute(connection, "INSERT INTO consultant VALUES ('owner',41)");
      execute(connection, "INSERT INTO chat VALUES (1,'!first:synthetic.test',0,'owner')");
      migration.update(new Contexts(), new LabelExpression());
      migration.rollback(1, new Contexts(), new LabelExpression());
      assertThat(count(connection, "chat")).isEqualTo(1);
      assertThatThrownBy(() -> count(connection, "chat_matrix_room"))
          .isInstanceOf(SQLException.class);
    }
  }

  protected Connection database() throws Exception {
    var connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:group-room-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
    execute(
        connection,
        "CREATE TABLE consultant (consultant_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT)");
    execute(
        connection,
        "CREATE TABLE chat (id BIGINT PRIMARY KEY,matrix_room_id VARCHAR(512),current_occurrence_index INT,consultant_id_owner VARCHAR(36))");
    return connection;
  }

  protected Liquibase migration(Connection connection) throws Exception {
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    return new Liquibase(
        "db/changelog/changeset/20261028_chat_matrix_room_lineage/changeSet.xml",
        new ClassLoaderResourceAccessor(),
        database);
  }

  protected void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  protected int count(Connection connection, String table) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      result.next();
      return result.getInt(1);
    }
  }
}
