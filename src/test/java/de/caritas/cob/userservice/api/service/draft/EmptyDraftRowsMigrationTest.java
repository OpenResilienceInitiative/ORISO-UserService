package de.caritas.cob.userservice.api.service.draft;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Replays the shipped Liquibase statement of changeset {@code 0083_empty_draft_rows} against an H2
 * database in MariaDB mode (the engine the testing profile uses), pinning the two properties the
 * migration has to have (#983).
 *
 * <p><b>Safety</b> — the migration must never delete a row a user would recognise as a draft. This
 * matters most for end-to-end encrypted drafts: they are opaque ciphertext, so a careless "looks
 * empty" rule would destroy real counselling content. Asserted over every sample.
 *
 * <p><b>Effectiveness</b> — the migration must clear the zero-content shapes #983 is about: the
 * empty string the frontend's autosave wrote, TipTap's empty document, and whitespace entities.
 *
 * <p>The two sets are not identical by design: the SQL deliberately leaves rows made purely of
 * control whitespace or literal U+00A0/U+200B alone, because matching those needs backslash escapes
 * MariaDB and H2 read differently. {@link DraftContent} treats them as empty, so the write path
 * rejects them; see the comment in {@code migrate.sql}.
 */
class EmptyDraftRowsMigrationTest {

  private static final Path MIGRATE_SQL =
      Path.of("src/main/resources/db/changelog/changeset/0083_empty_draft_rows/migrate.sql");

  /** Shapes the migration is required to clear. */
  private static final List<String> ZERO_CONTENT_SAMPLES =
      Arrays.asList(
          null,
          "",
          "   ",
          "<p></p>",
          "<p><br></p>",
          "<p><br/></p>",
          "<p></p><p></p>",
          "&nbsp;",
          "&#160;",
          "&NBSP;",
          "<p>&nbsp;</p>",
          "<p> </p>",
          "<div><span> </span></div>");

  /** Shapes the migration must never touch. */
  private static final List<String> CONTENT_SAMPLES =
      Arrays.asList(
          "Hello",
          "<p>Hello</p>",
          "<p>&nbsp;Hello&nbsp;</p>",
          "a",
          "AwgBmE3yLpFhZ0uK+base64ciphertext==",
          "{\"ciphertext\":\"AwgB\",\"ephemeral\":\"key\"}");

  /**
   * Empty per {@link DraftContent}, but knowingly out of reach of the portable SQL. Listed so the
   * gap is visible and intentional rather than an accident.
   */
  private static final List<String> TOLERATED_SURVIVOR_SAMPLES =
      Arrays.asList("\n", "\t\r\n", "\u00a0", "\u200b", "<p>\u00a0</p>");

  private Connection connection;

  @BeforeEach
  void setUp() throws SQLException {
    connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:draft-migration-test;MODE=MariaDB;DB_CLOSE_DELAY=0");
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE draft_message (
            id BIGINT NOT NULL AUTO_INCREMENT,
            user_id VARCHAR(64) NOT NULL,
            scope_key VARCHAR(255) NOT NULL,
            text TEXT NULL,
            action_path VARCHAR(512) NULL,
            title VARCHAR(255) NULL,
            source_session_id BIGINT NULL,
            room_ref VARCHAR(255) NULL,
            thread_root_id VARCHAR(255) NULL,
            create_date DATETIME NOT NULL,
            update_date DATETIME NOT NULL,
            tenant_id BIGINT NULL,
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
  void migration_clearsEveryZeroContentShape() throws SQLException {
    Map<Long, String> rows = insertAll(ZERO_CONTENT_SAMPLES);

    runMigration();

    assertThat(survivingIds())
        .as("every zero-content draft shape from #983 is gone")
        .doesNotContainAnyElementsOf(rows.keySet());
  }

  @Test
  void migration_neverDeletesADraftThatCarriesContent() throws SQLException {
    Map<Long, String> rows = insertAll(CONTENT_SAMPLES);

    runMigration();

    assertThat(survivingIds())
        .as("real text and opaque E2EE ciphertext survive untouched")
        .containsAll(rows.keySet());
  }

  /**
   * The invariant that must hold no matter which samples are added later: a row the migration
   * removes is always one {@link DraftContent} also considers empty. The reverse is allowed — the
   * SQL may leave an empty row behind, it may never remove a non-empty one.
   */
  @Test
  void migration_deletesOnlyRowsTheContentCheckAlsoCallsEmpty() throws SQLException {
    List<String> allSamples = new ArrayList<>();
    allSamples.addAll(ZERO_CONTENT_SAMPLES);
    allSamples.addAll(CONTENT_SAMPLES);
    allSamples.addAll(TOLERATED_SURVIVOR_SAMPLES);
    Map<Long, String> rows = insertAll(allSamples);

    runMigration();

    Set<Long> survivors = survivingIds();
    for (Map.Entry<Long, String> row : rows.entrySet()) {
      if (!survivors.contains(row.getKey())) {
        assertThat(DraftContent.hasContent(row.getValue()))
            .as(
                "migration deleted row %d (%s), which must be empty",
                row.getKey(), printable(row.getValue()))
            .isFalse();
      }
    }
  }

  @Test
  void toleratedSurvivors_areEmptyForTheWritePathEvenThoughTheMigrationSkipsThem() {
    for (String sample : TOLERATED_SURVIVOR_SAMPLES) {
      assertThat(DraftContent.hasContent(sample))
          .as("%s is rejected on write even though the migration leaves it", printable(sample))
          .isFalse();
    }
  }

  private Map<Long, String> insertAll(List<String> samples) throws SQLException {
    Map<Long, String> rows = new TreeMap<>();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO draft_message (user_id, scope_key, text, create_date, update_date)"
                + " VALUES (?, ?, ?, NOW(), NOW())",
            Statement.RETURN_GENERATED_KEYS)) {
      for (String sample : samples) {
        insert.setString(1, "user");
        insert.setString(2, "scope-" + rows.size());
        insert.setString(3, sample);
        insert.executeUpdate();
        try (var keys = insert.getGeneratedKeys()) {
          keys.next();
          rows.put(keys.getLong(1), sample);
        }
      }
    }
    return rows;
  }

  private void runMigration() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(readMigrationSql());
    }
  }

  private Set<Long> survivingIds() throws SQLException {
    Set<Long> survivors = new HashSet<>();
    try (Statement statement = connection.createStatement();
        var resultSet = statement.executeQuery("SELECT id FROM draft_message")) {
      while (resultSet.next()) {
        survivors.add(resultSet.getLong("id"));
      }
    }
    return survivors;
  }

  private static String printable(String value) {
    if (value == null) {
      return "<null>";
    }
    return value
        .replace("\u00a0", "\\u00a0")
        .replace("\u200b", "\\u200b")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
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
