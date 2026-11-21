package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.command.CommandFactory;
import liquibase.command.core.AbstractUpdateCommandStep;
import liquibase.command.core.UpdateCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Proves that the canonical changelog converges after a divergent hot-deploy removed {@code
 * support_room} but left changeset 0079 recorded as executed. The shared test schema must regain
 * the later lifecycle columns and index too, so Hibernate validation can still run after this
 * fixture.
 *
 * <p>Liquibase's {@code update} command keeps a JVM-wide "database is up to date" fast-check cache
 * (an instance field on the {@link AbstractUpdateCommandStep} singleton, keyed by
 * contexts/labels/schema/catalog/url/changelog). When another test in the same surefire JVM (e.g.
 * {@code DatabaseChangelogDriftIT} via Spring Boot) has already migrated the database, the first
 * update here is reported "up to date" and cached — and the second update then silently skips
 * deployment even though this test has meanwhile deleted the repair row from DATABASECHANGELOG. The
 * fast check is therefore disabled for this class and restored to its previous per-step state
 * afterwards; it is only an optimization and carries no semantics.
 */
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class SupportRoomMigrationConvergenceIT {

  private static final String CHANGELOG = "db/changelog/userservice-master.xml";
  private static final String REPAIR_CHANGESET_ID = "0088-support-room-repair";
  private static final String REPAIR_CHANGESET_AUTHOR = "frank";
  private static final String REPAIR_CHANGESET_FILE =
      "db/changelog/changeset/0088_support_room_repair/0088_changeSet.xml";

  /** The lookup indexes the full canonical changelog must restore, in column order. */
  private static final Map<String, List<String>> EXPECTED_INDEX_COLUMNS =
      Map.of(
          "idx_support_room_status_expiry", List.of("status", "expiry_date"),
          "idx_support_room_consultant", List.of("consultant_id", "status"),
          "idx_support_room_support_admin", List.of("support_admin_id", "status"),
          "idx_support_room_status_retry", List.of("status", "retry_at"));

  private static final Map<AbstractUpdateCommandStep, Boolean> previousFastCheckState =
      new HashMap<>();

  @BeforeAll
  static void disableLiquibaseUpToDateFastCheck() {
    CommandFactory.getInstance()
        .getCommandDefinition(UpdateCommandStep.COMMAND_NAME)
        .getPipeline()
        .stream()
        .filter(AbstractUpdateCommandStep.class::isInstance)
        .map(AbstractUpdateCommandStep.class::cast)
        .forEach(
            step -> {
              previousFastCheckState.put(step, readFastCheckEnabled(step));
              step.setFastCheckEnabled(false);
            });
  }

  @AfterAll
  static void restoreLiquibaseUpToDateFastCheck() {
    previousFastCheckState.forEach(AbstractUpdateCommandStep::setFastCheckEnabled);
    previousFastCheckState.clear();
  }

  /**
   * The command step exposes only a setter, so the pre-test state must be read reflectively to
   * restore exactly what another suite in this shared JVM may have configured.
   */
  private static boolean readFastCheckEnabled(AbstractUpdateCommandStep step) {
    try {
      Field field = AbstractUpdateCommandStep.class.getDeclaredField("isFastCheckEnabled");
      field.setAccessible(true);
      return field.getBoolean(step);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read Liquibase fast-check state", e);
    }
  }

  @Test
  void canonicalChangelogRepairsSupportRoomRemovedByDivergentMigration() throws Exception {
    updateCanonicalChangelog();

    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS userservice.support_room");
      deleteRepairChangelogRow(statement);
      deleteLifecycleChangelogRows(statement);
    }

    assertThat(supportRoomExists()).as("drift fixture removed support_room").isFalse();

    updateCanonicalChangelog();

    assertThat(supportRoomExists())
        .as("canonical changelog must converge after support_room was removed")
        .isTrue();
    assertSupportRoomIndexes("convergence must restore the lookup indexes");
    assertSupportRoomStatusColumn();
  }

  @Test
  void repairIsIdempotentWhenSupportRoomAlreadyExists() throws Exception {
    updateCanonicalChangelog();
    assertThat(supportRoomExists()).as("baseline: support_room present").isTrue();

    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      deleteRepairChangelogRow(statement);
    }

    updateCanonicalChangelog();

    assertThat(supportRoomExists())
        .as("rerunning the repair over an existing schema must not fail or drop the table")
        .isTrue();
    assertSupportRoomIndexes("rerunning the repair must leave the lookup indexes intact");
    assertSupportRoomStatusColumn();
  }

  @Test
  void canonicalChangelogCreatesSupportRoomCreationIntentWithRetryIndex() throws Exception {
    updateCanonicalChangelog();

    assertThat(indexColumns("support_room_creation_intent"))
        .containsEntry("PRIMARY", List.of("handshake_id"))
        .containsEntry("uk_support_room_creation_id", List.of("id"))
        .containsEntry("idx_support_room_creation_retry", List.of("retry_at", "handshake_id"));
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                """
                SELECT column_name, datetime_precision, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'userservice'
                  AND table_name = 'support_room_creation_intent'
                  AND column_name IN ('expires_at', 'retry_at')
                ORDER BY column_name
                """)) {
      for (var column : List.of("expires_at", "retry_at")) {
        assertThat(result.next()).as("%s column exists", column).isTrue();
        assertThat(result.getString("column_name")).isEqualTo(column);
        assertThat(result.getInt("datetime_precision")).isEqualTo(6);
        assertThat(result.getString("is_nullable")).isEqualTo("NO");
      }
    }
  }

  private void deleteRepairChangelogRow(Statement statement) throws SQLException {
    int deletedRows =
        statement.executeUpdate(
            "DELETE FROM userservice.DATABASECHANGELOG"
                + " WHERE ID = '"
                + REPAIR_CHANGESET_ID
                + "' AND AUTHOR = '"
                + REPAIR_CHANGESET_AUTHOR
                + "' AND FILENAME = '"
                + REPAIR_CHANGESET_FILE
                + "'");
    assertThat(deletedRows)
        .as(
            "exactly one recorded execution of the repair changeset must be deleted —"
                + " zero means the changeset identity in this test no longer matches"
                + " the changelog and the reruns would silently test nothing")
        .isEqualTo(1);
  }

  private void deleteLifecycleChangelogRows(Statement statement) throws SQLException {
    int deletedRows =
        statement.executeUpdate(
            """
            DELETE FROM userservice.DATABASECHANGELOG
            WHERE AUTHOR = 'oriso'
              AND FILENAME = 'db/changelog/changeset/20261119_support_room_lifecycle_904/changeSet.xml'
              AND ID IN (
                '20261119-support-room-lifecycle-status-904',
                '20261119-support-room-lifecycle-retry-904',
                '20261119-support-room-lifecycle-retry-index-904'
              )
            """);
    assertThat(deletedRows)
        .as("all three changesets that affect the dropped support room must replay")
        .isEqualTo(3);
  }

  private void assertSupportRoomStatusColumn() throws SQLException {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                """
                SELECT character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'userservice'
                  AND table_name = 'support_room'
                  AND column_name = 'status'
                """)) {
      assertThat(result.next()).as("support room status column exists").isTrue();
      assertThat(result.getInt("character_maximum_length"))
          .as("support room status can store REVOCATION_PENDING")
          .isGreaterThanOrEqualTo("REVOCATION_PENDING".length());
      assertThat(result.getString("is_nullable")).isEqualTo("NO");
    }
  }

  private void assertSupportRoomIndexes(String description) throws SQLException {
    Map<String, List<String>> actual = indexColumns("support_room");
    EXPECTED_INDEX_COLUMNS.forEach(
        (name, columns) ->
            assertThat(actual.get(name)).as("%s (%s)", description, name).isEqualTo(columns));
  }

  private Map<String, List<String>> indexColumns(String table) throws SQLException {
    Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
    try (Connection connection = openConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT index_name, column_name FROM information_schema.statistics
                WHERE table_schema = 'userservice' AND table_name = ?
                ORDER BY index_name, seq_in_index
                """)) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          columnsByIndex
              .computeIfAbsent(result.getString("index_name"), key -> new ArrayList<>())
              .add(result.getString("column_name"));
        }
      }
    }
    return columnsByIndex;
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
