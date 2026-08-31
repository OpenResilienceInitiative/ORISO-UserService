package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replays the shipped Liquibase statement of changeset {@code
 * 0084_event_notification_explanation_backfill} against H2 in MariaDB mode (#1010, task 1d).
 *
 * <p>The counsellor-written handover explanation was formatted into {@code event_notification.text}
 * and kept there indefinitely. Stopping new writes only helps going forward — the rows already
 * stored are the actual finding, so the backfill is the part that closes it.
 */
class ExplanationBackfillMigrationTest {

  private static final Path MIGRATE_SQL =
      Path.of(
          "src/main/resources/db/changelog/changeset/"
              + "0084_event_notification_explanation_backfill/migrate.sql");

  private Connection connection;

  @BeforeEach
  void setUp() throws SQLException {
    connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:explanation-backfill-test;MODE=MariaDB;DB_CLOSE_DELAY=0");
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE event_notification (
            id BIGINT NOT NULL AUTO_INCREMENT,
            recipient_user_id VARCHAR(64) NOT NULL,
            event_type VARCHAR(100) NOT NULL,
            category VARCHAR(20) NOT NULL,
            title VARCHAR(255) NOT NULL,
            text TEXT NULL,
            PRIMARY KEY (id)
          )
          """);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP ALL OBJECTS");
    }
    connection.close();
  }

  @Test
  void backfill_removesTheExplanationAndKeepsTheGeneratedPrefix() throws SQLException {
    insert(
        "case.handover.granted",
        "Dr. Muster took over your case. Reason: Counsellor is ill."
            + " Explanation: Client disclosed self-harm at the last session.");

    runMigration();

    assertThat(textsOf())
        .containsExactly("Dr. Muster took over your case. Reason: Counsellor is ill.");
  }

  @Test
  void backfill_alsoStripsAnExplanationThatRepeatsTheMarker() throws SQLException {
    insert(
        "case.handover.consent.requested",
        "Dr. Muster requested access to your case. Reason: Cover."
            + " Explanation: see below. Explanation: still free text.");

    runMigration();

    assertThat(textsOf())
        .as("cutting at the first marker removes everything after it")
        .containsExactly("Dr. Muster requested access to your case. Reason: Cover.");
  }

  @Test
  void backfill_leavesRowsWithoutAnExplanationUntouched() throws SQLException {
    insert("case.handover.consent.declined", "Client consent was declined for case #12. Reason: X");
    insert("inquiry.accepted", "Your request was accepted by Dr. Muster. Chat is now active.");
    insert("conversation.finished", null);

    runMigration();

    assertThat(textsOf())
        .containsExactly(
            "Client consent was declined for case #12. Reason: X",
            "Your request was accepted by Dr. Muster. Chat is now active.",
            null);
  }

  @Test
  void backfill_leavesNoRowContainingTheExplanationMarker() throws SQLException {
    insert("case.handover.granted", "A took over your case. Reason: R. Explanation: secret");
    insert("case.handover.consent.requested", "B requested access. Reason: R. Explanation: secret");
    insert("inquiry.accepted", "no marker here");

    runMigration();

    assertThat(textsOf())
        .as("no stored notification may still carry counsellor free text")
        .noneMatch(text -> text != null && text.contains("Explanation"));
  }

  private void insert(String eventType, String text) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO event_notification"
                + " (recipient_user_id, event_type, category, title, text)"
                + " VALUES ('user', ?, 'SYSTEM', 'title', ?)")) {
      insert.setString(1, eventType);
      insert.setString(2, text);
      insert.executeUpdate();
    }
  }

  private void runMigration() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(readMigrationSql());
    }
  }

  private List<String> textsOf() throws SQLException {
    List<String> texts = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        var resultSet = statement.executeQuery("SELECT text FROM event_notification ORDER BY id")) {
      while (resultSet.next()) {
        texts.add(resultSet.getString("text"));
      }
    }
    return texts;
  }

  /** Reads the shipped SQL exactly as Liquibase does, minus the comment lines it strips. */
  private static String readMigrationSql() {
    try {
      return Files.readString(MIGRATE_SQL, StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.trim().startsWith("--"))
          .reduce("", (a, b) -> a + "\n" + b);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
