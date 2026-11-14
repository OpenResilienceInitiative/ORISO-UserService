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

        assertThat(accessMode(c, 1))
            .as("legacy temporary links stay guests-only")
            .isEqualTo("GUESTS_ONLY");
        assertThat(accessMode(c, 2)).isEqualTo("ACCOUNTS_ONLY");
        assertThat(accessMode(c, 3)).as("call share links keep their own rules").isNull();
        try (var sql = c.createStatement()) {
          sql.execute("INSERT INTO session (id) VALUES (500)");
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
        }
      }
    }
  }

  private static String accessMode(Connection c, long id) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT access_mode FROM agency_invite_link WHERE id = " + id)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
