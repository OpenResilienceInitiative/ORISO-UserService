package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

/**
 * ORISO-Frontend#2252 on MariaDB, where the checks of this changelog run in production: they pass
 * on the tables the changelog creates, stop on a key that points at the wrong table, and the member
 * read of a deletion sees a reservation committed after its first read.
 */
class DirectInvitationAppointmentMigrationMariaDbIT {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261114_direct_invitation_appointment_2252/changeSet.xml";

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @Test
  void update_Should_Pass_AndPassAgain_OnTheTablesItCreates() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      update(c);
      update(c);

      assertThat(count(c, "DATABASECHANGELOG", "ID = '20261114-direct-invitation-member-2252'"))
          .isEqualTo(1);
      assertThat(
              count(
                  c,
                  "information_schema.columns",
                  "table_schema = DATABASE() AND table_name = 'direct_invitation_member'"
                      + " AND column_name = 'consented_legal_version_id' AND is_nullable = 'YES'"))
          .as("each member's own consent pointer (FE#2252, decision B)")
          .isEqualTo(1);
      assertThatThrownBy(
              () -> {
                exec(c, "INSERT INTO session (id) VALUES (1)");
                exec(c, member(1, "u"));
                exec(c, member(1, "u"));
              })
          .as("one membership per person and conversation")
          .isInstanceOf(SQLException.class);
    }
  }

  /** CodeRabbit on PR #1648: the key's name and cascade alone do not prove its target. */
  @Test
  void update_Should_Stop_When_TheMemberSessionKeyPointsAtAnotherTable() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      exec(c, "CREATE TABLE other_table (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
      exec(
          c,
          "CREATE TABLE direct_invitation_member (id BIGINT UNSIGNED AUTO_INCREMENT NOT NULL,"
              + " session_id BIGINT UNSIGNED NOT NULL, user_id VARCHAR(36) NOT NULL,"
              + " invite_link_id BIGINT UNSIGNED NOT NULL, guest BOOLEAN NOT NULL,"
              + " joined_at DATETIME NOT NULL, revoke_due_at DATETIME, removed_at DATETIME,"
              + " PRIMARY KEY (id), CONSTRAINT uq_direct_invitation_member_session_user UNIQUE"
              + " (session_id, user_id), CONSTRAINT fk_direct_invitation_member_session FOREIGN"
              + " KEY (session_id) REFERENCES other_table (id) ON DELETE CASCADE)");

      assertThatThrownBy(() -> update(c)).hasStackTraceContaining("direct_invitation_member");
    }
  }

  @Test
  void update_Should_Stop_When_TheReplacedTokenKeyPointsAtAnotherTable() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      exec(c, "CREATE TABLE other_table (id BIGINT NOT NULL, PRIMARY KEY (id))");
      exec(
          c,
          "CREATE TABLE agency_invite_link_replaced_token (invite_link_id BIGINT NOT NULL,"
              + " token VARCHAR(64) NOT NULL, CONSTRAINT uq_agency_invite_link_replaced_token"
              + " UNIQUE (token), CONSTRAINT fk_agency_invite_link_replaced_token_link FOREIGN KEY"
              + " (invite_link_id) REFERENCES other_table (id) ON DELETE CASCADE)");

      assertThatThrownBy(() -> update(c))
          .hasStackTraceContaining("agency_invite_link_replaced_token");
    }
  }

  /**
   * CodeRabbit on PR #1648: under REPEATABLE READ the deletion's first read fixes its snapshot. A
   * reservation committed after it is invisible to a plain read of the members and visible to the
   * locking read the deletion uses.
   */
  @Test
  void lockingMemberRead_Should_SeeAReservationCommittedAfterTheFirstRead() throws Exception {
    try (Connection setup = connect()) {
      reset(setup);
      update(setup);
      exec(setup, "INSERT INTO session (id) VALUES (7)");
    }
    try (Connection deletion = connect();
        Connection reservation = connect()) {
      deletion.setAutoCommit(false);
      deletion.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      // The deletion reads the conversation first, as SessionDeleteService does.
      assertThat(count(deletion, "session", "id = 7")).isEqualTo(1);
      exec(reservation, member(7, "late"));
      assertThat(count(deletion, "session", "id = 7 FOR UPDATE")).isEqualTo(1);

      assertThat(
              count(deletion, "direct_invitation_member", "session_id = 7 AND removed_at IS NULL"))
          .as("a plain read misses it")
          .isZero();
      assertThat(
              count(
                  deletion,
                  "direct_invitation_member",
                  "session_id = 7 AND removed_at IS NULL FOR UPDATE"))
          .as("the locking read sees it")
          .isEqualTo(1);
      deletion.rollback();
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  /** The tables as they are before this change. */
  private static void reset(Connection c) throws SQLException {
    exec(c, "SET FOREIGN_KEY_CHECKS = 0");
    for (var table :
        new String[] {
          "direct_invitation_member",
          "agency_invite_link_replaced_token",
          "other_table",
          "session_appointment",
          "agency_invite_link",
          "session",
          "DATABASECHANGELOG",
          "DATABASECHANGELOGLOCK"
        }) {
      exec(c, "DROP TABLE IF EXISTS " + table);
    }
    exec(c, "SET FOREIGN_KEY_CHECKS = 1");
    exec(c, "CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
    exec(
        c,
        "CREATE TABLE session_appointment (id CHAR(36) NOT NULL, session_id BIGINT UNSIGNED"
            + " NOT NULL, start_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,"
            + " status VARCHAR(16) NOT NULL, PRIMARY KEY (id))");
    exec(
        c,
        "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, link_kind VARCHAR(32) NOT NULL,"
            + " temporary_account BOOLEAN NOT NULL, PRIMARY KEY (id))");
  }

  private static String member(long sessionId, String userId) {
    return "INSERT INTO direct_invitation_member (session_id, user_id, invite_link_id, guest,"
        + " joined_at) VALUES ("
        + sessionId
        + ", '"
        + userId
        + "', 1, TRUE, '2030-01-01 10:00:00')";
  }

  private static void update(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    liquibase.update(new Contexts(), new LabelExpression());
    if (!c.getAutoCommit()) c.commit();
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
