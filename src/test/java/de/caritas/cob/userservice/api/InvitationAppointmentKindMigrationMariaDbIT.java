package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Appointments of counsellor direct invitations get the kind of their link's medium; appointments
 * with a kind and appointments of no invitation stay as they are.
 */
class InvitationAppointmentKindMigrationMariaDbIT {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261121_invitation_appointment_kind_1747/changeSet.xml";

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @Test
  void invitationAppointmentsGetTheKindOfTheirLinksMedium() throws Exception {
    try (Connection c = connect()) {
      reset(c);
      appointment(c, "chat", null);
      appointment(c, "audio", null);
      appointment(c, "video", null);
      appointment(c, "set", "ONSITE");
      appointment(c, "plain", null);
      appointment(c, "live", null);
      link(c, "CHAT", "chat");
      link(c, "AUDIO", "audio");
      link(c, "VIDEO", "video");
      link(c, "CHAT", "set");
      link(c, "LIVE_CHAT", "live");
      link(c, "VIDEO", null);

      update(c);

      assertThat(kind(c, "chat")).isEqualTo("CHAT");
      assertThat(kind(c, "audio")).isEqualTo("PHONE");
      assertThat(kind(c, "video")).isEqualTo("VIDEO");
      assertThat(kind(c, "set")).as("a kind already stored stays").isEqualTo("ONSITE");
      assertThat(kind(c, "plain")).as("no invitation, no kind").isNull();
      assertThat(kind(c, "live")).as("no call medium, no kind").isNull();
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static void reset(Connection c) throws SQLException {
    exec(c, "DROP TABLE IF EXISTS agency_invite_link");
    exec(c, "DROP TABLE IF EXISTS session_appointment");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOG");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
    exec(c, "CREATE TABLE session_appointment (id CHAR(36) PRIMARY KEY, kind VARCHAR(16))");
    exec(
        c,
        "CREATE TABLE agency_invite_link (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + " chat_type VARCHAR(32) NOT NULL, appointment_id CHAR(36))");
  }

  private static void appointment(Connection c, String id, String kind) throws SQLException {
    exec(
        c,
        "INSERT INTO session_appointment (id, kind) VALUES ('"
            + id
            + "', "
            + (kind == null ? "NULL" : "'" + kind + "'")
            + ")");
  }

  private static void link(Connection c, String chatType, String appointmentId)
      throws SQLException {
    exec(
        c,
        "INSERT INTO agency_invite_link (chat_type, appointment_id) VALUES ('"
            + chatType
            + "', "
            + (appointmentId == null ? "NULL" : "'" + appointmentId + "'")
            + ")");
  }

  private static String kind(Connection c, String id) throws SQLException {
    try (var sql = c.createStatement();
        var rows =
            sql.executeQuery("SELECT kind FROM session_appointment WHERE id = '" + id + "'")) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static void update(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    liquibase.update(new Contexts(), new LabelExpression());
    if (!c.getAutoCommit()) c.commit();
  }

  private static void exec(Connection c, String statement) throws SQLException {
    try (var sql = c.createStatement()) {
      sql.execute(statement);
    }
  }
}
