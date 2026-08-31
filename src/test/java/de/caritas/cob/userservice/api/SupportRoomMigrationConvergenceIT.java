package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Proves that the canonical changelog converges after a divergent hot-deploy removed {@code
 * support_room} but left changeset 0079 recorded as executed.
 */
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class SupportRoomMigrationConvergenceIT {

  private static final String CHANGELOG = "db/changelog/userservice-master.xml";
  private static final String REPAIR_CHANGESET_ID = "0086-support-room-repair";
  private static final String REPAIR_CHANGESET_AUTHOR = "frank";
  private static final String REPAIR_CHANGESET_FILE =
      "db/changelog/changeset/0086_support_room_repair/0086_changeSet.xml";

  @Test
  void canonicalChangelogRepairsSupportRoomRemovedByDivergentMigration() throws Exception {
    updateCanonicalChangelog();

    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS userservice.support_room");
      statement.executeUpdate(
          "DELETE FROM userservice.DATABASECHANGELOG"
              + " WHERE ID = '"
              + REPAIR_CHANGESET_ID
              + "' AND AUTHOR = '"
              + REPAIR_CHANGESET_AUTHOR
              + "' AND FILENAME = '"
              + REPAIR_CHANGESET_FILE
              + "'");
    }

    assertThat(supportRoomExists()).as("drift fixture removed support_room").isFalse();

    updateCanonicalChangelog();

    assertThat(supportRoomExists())
        .as("canonical changelog must converge after support_room was removed")
        .isTrue();
  }

  private void updateCanonicalChangelog() throws Exception {
    try (Connection connection = openConnection()) {
      Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (Liquibase liquibase =
          new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts("prod"), new LabelExpression());
      }
    }
  }

  private boolean supportRoomExists() throws SQLException {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = 'userservice' AND table_name = 'support_room'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        System.getenv("LIQUIBASE_IT_DB_URL"),
        System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"),
        System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
  }
}
