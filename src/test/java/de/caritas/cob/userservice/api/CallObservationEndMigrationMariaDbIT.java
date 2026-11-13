package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

/**
 * #1628 on MariaDB: the END_TOMBSTONE rows of matrix_call_observation move into the per-person end
 * table, the registrations stay, and every observation names its media room again.
 */
class CallObservationEndMigrationMariaDbIT {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261113_call_observation_end_1628/changeSet.xml";

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @Test
  void tombstonesMoveIntoTheEndTableAndRegistrationsStay() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      try (var sql = c.createStatement()) {
        sql.execute(tombstone("!room:x", "early", "@creator:x", 100L));
        sql.execute(tombstone("!room:x", "early-2", "@other:x", 200L));
        sql.execute(
            "INSERT INTO matrix_call_observation (origin, source_room_id, call_id, media_room_id,"
                + " creator_matrix_id, tenant_id, chat_id, session_id, video, registered_at,"
                + " last_seen_at, expires_at) VALUES ('CREATOR_REGISTRATION', '!room:x', 'live',"
                + " '!media:x', '@creator:x', 7, NULL, 45, true, 1, 1, 2)");
      }
      update(c);

      assertThat(ends(c))
          .containsExactlyInAnyOrder(
              "!room:x|early|@creator:x|7|45|100", "!room:x|early-2|@other:x|7|45|200");
      assertThat(count(c, "matrix_call_observation", "origin = 'END_TOMBSTONE'")).isZero();
      assertThat(count(c, "matrix_call_observation", "call_id = 'live'")).isEqualTo(1);
      assertThatThrownBy(() -> exec(c, tombstone("!room:x", "again", "@creator:x", 300L)))
          .as("media_room_id is required again")
          .isInstanceOf(SQLException.class);
      // One stored end per person and call.
      assertThatThrownBy(
              () ->
                  exec(
                      c,
                      "INSERT INTO matrix_call_observation_end (source_room_id, call_id,"
                          + " actor_matrix_id, tenant_id, ended_at) VALUES ('!room:x', 'early',"
                          + " '@creator:x', 7, 5)"))
          .isInstanceOf(SQLException.class);
      exec(
          c,
          "INSERT INTO matrix_call_observation_end (source_room_id, call_id, actor_matrix_id,"
              + " tenant_id, ended_at) VALUES ('!room:x', 'early', '@other:x', 7, 5)");
    }
  }

  /** The master changelog's rule: safe on a database that already received the table by hand. */
  @Test
  void anEndTableThatIsAlreadyThereIsMarkedRanAndTheTombstonesStillMove() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      exec(
          c,
          "CREATE TABLE matrix_call_observation_end (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
              + " source_room_id VARCHAR(255) NOT NULL, call_id VARCHAR(191) NOT NULL,"
              + " actor_matrix_id VARCHAR(255) NOT NULL, tenant_id BIGINT NOT NULL, chat_id BIGINT,"
              + " session_id BIGINT, ended_at BIGINT NOT NULL,"
              + " CONSTRAINT uk_call_observation_end_actor"
              + " UNIQUE (source_room_id, call_id, actor_matrix_id))");
      // The end of this tombstone is already in the table: it is not copied twice.
      exec(
          c,
          "INSERT INTO matrix_call_observation_end (source_room_id, call_id, actor_matrix_id,"
              + " tenant_id, session_id, ended_at) VALUES ('!room:x', 'early', '@creator:x', 7, 45,"
              + " 100)");
      exec(c, tombstone("!room:x", "early", "@creator:x", 100L));

      update(c);

      assertThat(execType(c, "20261113-matrix-call-observation-end-1628")).isEqualTo("MARK_RAN");
      assertThat(ends(c)).containsExactly("!room:x|early|@creator:x|7|45|100");
      assertThat(count(c, "matrix_call_observation", "origin = 'END_TOMBSTONE'")).isZero();
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  /** The table as the #2166, #2237 and #1628 lease changesets leave it. */
  private static void reset(Connection c) throws SQLException {
    exec(c, "DROP TABLE IF EXISTS matrix_call_observation_end");
    exec(c, "DROP TABLE IF EXISTS matrix_call_observation");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOG");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
    exec(
        c,
        "CREATE TABLE matrix_call_observation (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + " origin VARCHAR(40) NOT NULL, source_room_id VARCHAR(255) NOT NULL,"
            + " call_id VARCHAR(191) NOT NULL, media_room_id VARCHAR(255),"
            + " creator_matrix_id VARCHAR(255) NOT NULL, tenant_id BIGINT NOT NULL, chat_id BIGINT,"
            + " session_id BIGINT, video BOOLEAN NOT NULL, registered_at BIGINT NOT NULL,"
            + " invited_at BIGINT, last_seen_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,"
            + " ended_at BIGINT, liveness_checked_at BIGINT,"
            + " CONSTRAINT uk_call_observation_source_call UNIQUE (source_room_id, call_id),"
            + " CONSTRAINT uk_call_observation_media_room UNIQUE (media_room_id))");
  }

  private static void update(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    liquibase.update(new Contexts(), new LabelExpression());
    if (!c.getAutoCommit()) c.commit();
  }

  private static String tombstone(String room, String call, String ender, long endedAt) {
    return "INSERT INTO matrix_call_observation (origin, source_room_id, call_id, media_room_id,"
        + " creator_matrix_id, tenant_id, chat_id, session_id, video, registered_at, last_seen_at,"
        + " expires_at, ended_at) VALUES ('END_TOMBSTONE', '"
        + room
        + "', '"
        + call
        + "', NULL, '"
        + ender
        + "', 7, NULL, 45, false, "
        + endedAt
        + ", "
        + endedAt
        + ", "
        + endedAt
        + ", "
        + endedAt
        + ")";
  }

  private static List<String> ends(Connection c) throws SQLException {
    var result = new ArrayList<String>();
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT source_room_id, call_id, actor_matrix_id, tenant_id, session_id, ended_at"
                    + " FROM matrix_call_observation_end")) {
      while (rs.next()) {
        result.add(
            String.join(
                "|",
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)));
      }
    }
    return result;
  }

  private static String execType(Connection c, String id) throws SQLException {
    try (var sql = c.prepareStatement("SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = ?")) {
      sql.setString(1, id);
      try (var rs = sql.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static int count(Connection c, String table, String where) throws SQLException {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static void exec(Connection c, String statement) throws SQLException {
    try (var sql = c.createStatement()) {
      sql.execute(statement);
    }
    if (!c.getAutoCommit()) c.commit();
  }
}
