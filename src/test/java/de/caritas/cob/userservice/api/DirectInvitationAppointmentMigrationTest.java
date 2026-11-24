package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * ORISO-Frontend#2252: existing direct links get their access mode from the temporary-account
 * switch, an appointment can exist before its conversation, and invited members get a table.
 */
class DirectInvitationAppointmentMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261114_direct_invitation_appointment_2252/changeSet.xml";

  @Test
  void update_Should_MapAccessModeFromTemporaryFlag_And_AllowAnAppointmentWithoutSession()
      throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-invitation-"
                + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
            "sa",
            "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
        sql.execute(
            "CREATE TABLE session_appointment (id CHAR(36) NOT NULL, session_id BIGINT UNSIGNED"
                + " NOT NULL, start_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,"
                + " status VARCHAR(16) NOT NULL, PRIMARY KEY (id))");
        sql.execute(
            "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, link_kind VARCHAR(32) NOT NULL,"
                + " temporary_account BOOLEAN NOT NULL, PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO agency_invite_link VALUES (1, 'COUNSELLOR', TRUE),"
                + " (2, 'COUNSELLOR', FALSE), (3, 'CALL', TRUE)");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(accessMode(c, 1)).isEqualTo("BOTH");
        assertThat(accessMode(c, 2)).isEqualTo("ACCOUNTS_ONLY");
        assertThat(accessMode(c, 3)).as("call share links keep their own rules").isNull();
        try (var sql = c.createStatement()) {
          sql.execute("INSERT INTO session (id) VALUES (500)");
          try (var rs =
              sql.executeQuery(
                  "SELECT direct_invitation_conversation FROM session WHERE id = 500")) {
            rs.next();
            assertThat(rs.getBoolean(1))
                .as("an existing conversation is no invitation's")
                .isFalse();
          }
          // The appointment of an invitation nobody opened yet has no conversation.
          sql.execute(
              "INSERT INTO session_appointment (id, session_id, start_time, end_time, status)"
                  + " VALUES ('a', NULL, '2030-01-01 10:00:00', '2030-01-01 11:00:00',"
                  + " 'SCHEDULED')");
          sql.execute(
              "INSERT INTO direct_invitation_member (session_id, user_id, invite_link_id, guest,"
                  + " joined_at) VALUES (500, 'u', 1, TRUE, '2030-01-01 10:00:00')");
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO direct_invitation_member (session_id, user_id,"
                              + " invite_link_id, guest, joined_at) VALUES (500, 'u', 1, TRUE,"
                              + " '2030-01-01 10:00:00')"))
              .as("one membership per person and conversation")
              .isInstanceOf(java.sql.SQLException.class);
          sql.execute(
              "INSERT INTO agency_invite_link_replaced_token (invite_link_id, token)"
                  + " VALUES (1, 'old')");
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO agency_invite_link_replaced_token (invite_link_id, token)"
                              + " VALUES (2, 'old')"))
              .as("a replaced token belongs to one invitation")
              .isInstanceOf(java.sql.SQLException.class);
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO agency_invite_link_replaced_token (invite_link_id, token)"
                              + " VALUES (404, 'other')"))
              .as("only for an existing invitation")
              .isInstanceOf(java.sql.SQLException.class);
        }
      }
    }
  }

  /** MariaDB commits every DDL on its own: a rerun after a partial run finishes the rest. */
  @Test
  void update_Should_FinishIndexesAndBackfill_When_AnEarlierRunStoppedAfterTheColumns()
      throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-invitation-partial-"
                + UUID.randomUUID()
                + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
            "sa",
            "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
        sql.execute(
            "CREATE TABLE session_appointment (id CHAR(36) NOT NULL, session_id BIGINT UNSIGNED"
                + " NOT NULL, start_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,"
                + " status VARCHAR(16) NOT NULL, PRIMARY KEY (id))");
        // The state an interrupted run leaves: columns added, no index, no backfill.
        sql.execute(
            "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, link_kind VARCHAR(32) NOT NULL,"
                + " temporary_account BOOLEAN NOT NULL, appointment_id CHAR(36),"
                + " validity_after_end_minutes INT, access_mode VARCHAR(16),"
                + " joins_existing_session BOOLEAN DEFAULT FALSE NOT NULL, PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO agency_invite_link (id, link_kind, temporary_account) VALUES"
                + " (1, 'COUNSELLOR', TRUE), (2, 'COUNSELLOR', FALSE)");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(accessMode(c, 1)).isEqualTo("BOTH");
        assertThat(accessMode(c, 2)).isEqualTo("ACCOUNTS_ONLY");
        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.indexes WHERE index_name ="
                        + " 'idx_agency_invite_link_appointment'")) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
      }
    }
  }

  /**
   * CodeRabbit on PR #1648: a member table that exists without its keys is no finished migration.
   * The update stops and names it, instead of marking it done or repairing an unknown table.
   */
  @Test
  void update_Should_Stop_When_TheMemberTableExistsWithoutItsKeys() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-partial")) {
      givenTheTablesBeforeThisChange(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE direct_invitation_member (id BIGINT UNSIGNED AUTO_INCREMENT NOT NULL,"
                + " session_id BIGINT UNSIGNED NOT NULL, user_id VARCHAR(36) NOT NULL,"
                + " invite_link_id BIGINT UNSIGNED NOT NULL, guest BOOLEAN NOT NULL,"
                + " joined_at DATETIME NOT NULL, revoke_due_at DATETIME, removed_at DATETIME,"
                + " PRIMARY KEY (id))");
      }
      try (var liquibase = liquibase(c)) {
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("direct_invitation_member");
      }
    }
  }

  @Test
  void update_Should_Stop_When_TheReplacedTokenTableExistsWithoutItsKeys() throws Exception {
    try (Connection c = h2WithUpperCaseNames("token-partial")) {
      givenTheTablesBeforeThisChange(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE agency_invite_link_replaced_token (invite_link_id BIGINT NOT NULL,"
                + " token VARCHAR(64) NOT NULL)");
      }
      try (var liquibase = liquibase(c)) {
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("agency_invite_link_replaced_token");
      }
    }
  }

  /** The check runs on every start: complete tables pass it again. */
  @Test
  void update_Should_PassAgain_When_TheTablesAreComplete() throws Exception {
    try (Connection c = h2WithUpperCaseNames("rerun")) {
      givenTheTablesBeforeThisChange(c);
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        liquibase.update(new Contexts(), new LabelExpression());
      }
    }
  }

  /**
   * FE#2252 decision B: each member keeps their own consent pointer. Its own changeSet, so a
   * database whose member table came from an earlier run gets the column as well.
   */
  @Test
  void update_Should_AddTheMembersConsentPointer_ToAMemberTableFromAnEarlierRun() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-consent")) {
      givenTheTablesBeforeThisChange(c);
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute(
              "ALTER TABLE direct_invitation_member DROP COLUMN consented_legal_version_id");
          sql.execute("ALTER TABLE direct_invitation_member DROP COLUMN consent_given");
          sql.execute(
              "DELETE FROM DATABASECHANGELOG WHERE ID IN"
                  + " ('20261116-direct-invitation-member-consent-2252',"
                  + " '20261116-direct-invitation-member-consent-given-2252')");
        }
        liquibase.update(new Contexts(), new LabelExpression());
        // Read before closing: closing Liquibase closes its connection.
        assertTheMembersConsentColumns(c);
      }
    }
  }

  private static void assertTheMembersConsentColumns(Connection c) throws Exception {
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE"
                    + " TABLE_NAME = 'DIRECT_INVITATION_MEMBER'"
                    + " AND COLUMN_NAME = 'CONSENTED_LEGAL_VERSION_ID'")) {
      assertThat(rs.next()).as("the column exists").isTrue();
      assertThat(rs.getString(1)).isEqualTo("YES");
    }
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE"
                    + " TABLE_NAME = 'DIRECT_INVITATION_MEMBER' AND COLUMN_NAME = 'CONSENT_GIVEN'")) {
      assertThat(rs.next()).as("the agreed-without-version marker exists").isTrue();
      assertThat(rs.getString(1)).isEqualTo("NO");
      assertThat(rs.getString(2)).isEqualToIgnoringCase("FALSE");
    }
  }

  /** A table missing although its changeSet ran is no table to create again silently. */
  @Test
  void update_Should_Stop_When_TheMemberTableIsGoneAlthoughItsChangeSetRan() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-gone")) {
      givenTheTablesBeforeThisChange(c);
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute("DROP TABLE direct_invitation_member");
        }
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("direct_invitation_member");
      }
    }
  }

  /** The key's name alone is not enough: an index of that name that allows duplicates stops it. */
  @Test
  void update_Should_Stop_When_TheMemberKeyIsNotUnique() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-not-unique")) {
      givenTheTablesBeforeThisChange(c);
      try (var sql = c.createStatement()) {
        sql.execute(completeMemberTable());
        sql.execute(
            "CREATE INDEX uq_direct_invitation_member_session_user ON direct_invitation_member"
                + " (session_id, user_id)");
        sql.execute(
            "ALTER TABLE direct_invitation_member ADD CONSTRAINT"
                + " fk_direct_invitation_member_session FOREIGN KEY (session_id) REFERENCES"
                + " session (id) ON DELETE CASCADE");
      }
      try (var liquibase = liquibase(c)) {
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("direct_invitation_member");
      }
    }
  }

  /** A session key that keeps member rows of a deleted conversation stops it. */
  @Test
  void update_Should_Stop_When_TheMemberSessionKeyDoesNotCascade() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-no-cascade")) {
      givenTheTablesBeforeThisChange(c);
      try (var sql = c.createStatement()) {
        sql.execute(completeMemberTable());
        sql.execute(
            "ALTER TABLE direct_invitation_member ADD CONSTRAINT"
                + " uq_direct_invitation_member_session_user UNIQUE (session_id, user_id)");
        sql.execute(
            "ALTER TABLE direct_invitation_member ADD CONSTRAINT"
                + " fk_direct_invitation_member_session FOREIGN KEY (session_id) REFERENCES"
                + " session (id)");
      }
      try (var liquibase = liquibase(c)) {
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("direct_invitation_member");
      }
    }
  }

  /** CodeRabbit on PR #1648: a session key to another table stops it, on H2 as on MariaDB. */
  @Test
  void update_Should_Stop_When_TheMemberSessionKeyPointsAtAnotherTable() throws Exception {
    try (Connection c = h2WithUpperCaseNames("member-wrong-target")) {
      givenTheTablesBeforeThisChange(c);
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE other_table (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
        sql.execute(completeMemberTable());
        sql.execute(
            "ALTER TABLE direct_invitation_member ADD CONSTRAINT"
                + " uq_direct_invitation_member_session_user UNIQUE (session_id, user_id)");
        sql.execute(
            "ALTER TABLE direct_invitation_member ADD CONSTRAINT"
                + " fk_direct_invitation_member_session FOREIGN KEY (session_id) REFERENCES"
                + " other_table (id) ON DELETE CASCADE");
      }
      try (var liquibase = liquibase(c)) {
        assertThatThrownBy(() -> liquibase.update(new Contexts(), new LabelExpression()))
            .hasStackTraceContaining("direct_invitation_member");
      }
    }
  }

  private static String completeMemberTable() {
    return "CREATE TABLE direct_invitation_member (id BIGINT UNSIGNED AUTO_INCREMENT NOT NULL,"
        + " session_id BIGINT UNSIGNED NOT NULL, user_id VARCHAR(36) NOT NULL,"
        + " invite_link_id BIGINT UNSIGNED NOT NULL, guest BOOLEAN NOT NULL,"
        + " joined_at DATETIME NOT NULL, revoke_due_at DATETIME, removed_at DATETIME,"
        + " PRIMARY KEY (id))";
  }

  private static Connection h2WithUpperCaseNames(String name) throws Exception {
    // H2's default upper-case names, so the table-exists check finds the tables.
    return DriverManager.getConnection(
        "jdbc:h2:mem:direct-invitation-" + name + "-" + UUID.randomUUID() + ";MODE=MariaDB",
        "sa",
        "");
  }

  private static void givenTheTablesBeforeThisChange(Connection c) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute("CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
      sql.execute(
          "CREATE TABLE session_appointment (id CHAR(36) NOT NULL, session_id BIGINT UNSIGNED"
              + " NOT NULL, start_time DATETIME(6) NOT NULL, end_time DATETIME(6) NOT NULL,"
              + " status VARCHAR(16) NOT NULL, PRIMARY KEY (id))");
      sql.execute(
          "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, link_kind VARCHAR(32) NOT NULL,"
              + " temporary_account BOOLEAN NOT NULL, PRIMARY KEY (id))");
    }
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  private static String accessMode(Connection c, long id) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT access_mode FROM agency_invite_link WHERE id = " + id)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
