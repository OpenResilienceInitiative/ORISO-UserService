package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * The calendar changesets (ADR-032) on a database that already received them by hand, as the master
 * changelog asks of every schema changeset: an existing table or column is marked as ran, and a
 * table that was created without its index gets the index.
 */
class CalendarChangesetPreconditionsTest {

  private static final String DIRECTORY = "db/changelog/changeset/";
  private static final String BLOCKER = DIRECTORY + "20261114_calendar_blocker_1000/changeSet.xml";
  private static final String SYNC = DIRECTORY + "20261114_calendar_sync_1645/changeSet.xml";
  private static final String WORKING_HOURS =
      DIRECTORY + "20261114_calendar_working_hours_1000/changeSet.xml";
  private static final String KIND =
      DIRECTORY + "20261114_session_appointment_kind_1000/changeSet.xml";

  @Test
  void freshDatabase_Should_CreateEveryTable_And_ItsIndexOnce() throws Exception {
    try (Connection c = connection()) {
      createSessionAppointment(c, false);

      for (var changelog : new String[] {KIND, BLOCKER, SYNC, WORKING_HOURS}) {
        update(c, changelog);
      }

      assertThat(execTypes(c))
          .containsExactly(
              Map.entry("20261114-session-appointment-kind-1000", "EXECUTED"),
              Map.entry("20261114-calendar-blocker-1000", "EXECUTED"),
              Map.entry("20261114-calendar-blocker-1000-index", "MARK_RAN"),
              Map.entry("20261114-calendar-sync-1645", "EXECUTED"),
              Map.entry("20261114-calendar-working-hours-1000", "EXECUTED"),
              Map.entry("20261114-calendar-working-hours-1000-index", "MARK_RAN"));
      assertThat(hasIndex(c, "CALENDAR_BLOCKER", "IDX_CALENDAR_BLOCKER_CONSULTANT_START")).isTrue();
      assertThat(hasIndex(c, "CALENDAR_WORKING_HOURS", "IDX_CALENDAR_WORKING_HOURS_CONSULTANT"))
          .isTrue();
    }
  }

  @Test
  void schemaMadeByHand_Should_BeMarkedAsRan_And_GetAMissingIndex() throws Exception {
    try (Connection c = connection()) {
      createSessionAppointment(c, true);
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE calendar_blocker (id CHAR(36) NOT NULL, consultant_id VARCHAR(36) NOT"
                + " NULL, tenant_id BIGINT, start_time DATETIME(6) NOT NULL, end_time DATETIME(6)"
                + " NOT NULL, all_day BOOLEAN DEFAULT FALSE NOT NULL, note VARCHAR(255), series_id"
                + " CHAR(36), source VARCHAR(16) NOT NULL, create_date DATETIME(6) NOT NULL,"
                + " PRIMARY KEY (id))");
        sql.execute(
            "CREATE TABLE calendar_sync (consultant_id VARCHAR(36) NOT NULL, tenant_id BIGINT,"
                + " feed_id CHAR(36) NOT NULL, feed_last_fetched_at DATETIME(6), import_url"
                + " VARCHAR(2048), import_last_sync_at DATETIME(6), import_error VARCHAR(32),"
                + " PRIMARY KEY (consultant_id), CONSTRAINT uq_calendar_sync_feed_id UNIQUE"
                + " (feed_id))");
        sql.execute(
            "CREATE TABLE calendar_working_hours (id CHAR(36) NOT NULL, consultant_id VARCHAR(36)"
                + " NOT NULL, weekday INT NOT NULL, start_time TIME NOT NULL, end_time TIME NOT"
                + " NULL, PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO calendar_working_hours (id, consultant_id, weekday, start_time, end_time)"
                + " VALUES ('"
                + UUID.randomUUID()
                + "', 'synthetic-consultant', 1, '07:00:00', '20:00:00')");
      }
      if (!c.getAutoCommit()) {
        c.commit();
      }

      for (var changelog : new String[] {KIND, BLOCKER, SYNC, WORKING_HOURS}) {
        update(c, changelog);
      }

      assertThat(execTypes(c))
          .containsExactly(
              Map.entry("20261114-session-appointment-kind-1000", "MARK_RAN"),
              Map.entry("20261114-calendar-blocker-1000", "MARK_RAN"),
              Map.entry("20261114-calendar-blocker-1000-index", "EXECUTED"),
              Map.entry("20261114-calendar-sync-1645", "MARK_RAN"),
              Map.entry("20261114-calendar-working-hours-1000", "MARK_RAN"),
              Map.entry("20261114-calendar-working-hours-1000-index", "EXECUTED"));
      assertThat(hasIndex(c, "CALENDAR_BLOCKER", "IDX_CALENDAR_BLOCKER_CONSULTANT_START")).isTrue();
      assertThat(hasIndex(c, "CALENDAR_WORKING_HOURS", "IDX_CALENDAR_WORKING_HOURS_CONSULTANT"))
          .isTrue();
      try (var sql = c.createStatement();
          var rs = sql.executeQuery("SELECT COUNT(*) FROM calendar_working_hours")) {
        rs.next();
        assertThat(rs.getInt(1)).as("rows made by hand are kept").isEqualTo(1);
      }
    }
  }

  private static void createSessionAppointment(Connection c, boolean withKind) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE session_appointment (id CHAR(36) NOT NULL"
              + (withKind ? ", kind VARCHAR(16)" : "")
              + ", PRIMARY KEY (id))");
    }
  }

  private static void update(Connection c, String changelog) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    // Not closed: closing a Liquibase instance closes the connection the test still reads.
    new Liquibase(changelog, new ClassLoaderResourceAccessor(), database)
        .update(new Contexts(), new LabelExpression());
  }

  private static Map<String, String> execTypes(Connection c) throws Exception {
    var found = new LinkedHashMap<String, String>();
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT ID, EXECTYPE FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED, DATEEXECUTED")) {
      while (rs.next()) {
        found.put(rs.getString(1), rs.getString(2));
      }
    }
    return found;
  }

  private static boolean hasIndex(Connection c, String table, String index) throws Exception {
    try (var rs = c.getMetaData().getIndexInfo(null, null, table, false, false)) {
      while (rs.next()) {
        if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:calendar-preconditions-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
  }
}
