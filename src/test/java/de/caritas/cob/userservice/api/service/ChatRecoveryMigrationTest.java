package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
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

class ChatRecoveryMigrationTest {
  private static final String CHANGELOG =
      "db/changelog/changeset/20260910_chat_recovery_policy/changeSet.xml";
  private static final String OLD_CHECKSUM = "9:941bfe351974f96616c71122c14a2977";

  private Connection connect() throws Exception {
    String url = System.getProperty("recovery.migration.jdbcUrl");
    if (url != null && !url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/recovery_test")) {
      throw new IllegalArgumentException(
          "Only the disposable local recovery_test database is supported");
    }
    return DriverManager.getConnection(
        url == null
            ? "jdbc:h2:mem:recovery-" + UUID.randomUUID() + ";MODE=MariaDB;NON_KEYWORDS=USER"
            : url,
        "root",
        "");
  }

  private void resetFixture(Connection c) throws Exception {
    try (var statement = c.createStatement()) {
      for (var table :
          new String[] {"user", "consultant", "DATABASECHANGELOG", "DATABASECHANGELOGLOCK"}) {
        statement.execute("DROP TABLE IF EXISTS `" + table + "`");
      }
      for (var table : new String[] {"user", "consultant"}) {
        statement.execute("CREATE TABLE `" + table + "` (id VARCHAR(32) PRIMARY KEY)");
        statement.execute("INSERT INTO `" + table + "` VALUES ('legacy'), ('enrolled')");
      }
    }
  }

  @Test
  void partialDdlPrefixesAndFullRerunsPreserveEveryExistingValue() throws Exception {
    var statements =
        Files.readString(Path.of("src/main/resources/" + CHANGELOG).resolveSibling("migrate.sql"))
            .trim()
            .split(";");
    try (var c = connect();
        var sql = c.createStatement()) {
      for (int prefix = 0; prefix <= statements.length; prefix++) {
        resetFixture(c);
        for (int i = 0; i < prefix; i++) {
          sql.execute(statements[i]);
          String table = i < 2 ? "user" : "consultant";
          String assignment =
              i % 2 == 0
                  ? "chat_recovery_mode='LOGIN_PASSWORD'"
                  : "chat_recovery_policy_revision=7";
          sql.execute("UPDATE `" + table + "` SET " + assignment + " WHERE id='enrolled'");
        }
        for (var statement : statements) sql.execute(statement);
        for (int role = 0; role < 2; role++) {
          String table = role == 0 ? "user" : "consultant";
          try (var rows = sql.executeQuery("SELECT * FROM `" + table + "` ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(
                prefix > role * 2 ? "LOGIN_PASSWORD" : null, rows.getString("chat_recovery_mode"));
            assertEquals(
                prefix > role * 2 + 1 ? 7L : null,
                rows.getObject("chat_recovery_policy_revision", Long.class));
            assertTrue(rows.next());
            assertNull(rows.getString("chat_recovery_mode"));
            assertNull(rows.getObject("chat_recovery_policy_revision"));
            assertFalse(rows.next());
          }
        }
        for (var statement : statements) sql.execute(statement);
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM `user`")) {
          assertTrue(rows.next());
          assertEquals(2, rows.getInt(1));
        }
      }
    }
  }

  @Test
  void recordedPreDevChecksumIsAcceptedWithoutReapplyingMigration() throws Exception {
    try (var c = connect()) {
      resetFixture(c);
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute(
              "UPDATE DATABASECHANGELOG SET MD5SUM='"
                  + OLD_CHECKSUM
                  + "' WHERE ID='20260910-chat-recovery-policy'");
          sql.execute(
              "UPDATE `user` SET chat_recovery_mode='LOGIN_PASSWORD', chat_recovery_policy_revision=7 WHERE id='enrolled'");
        }
        c.commit();
        liquibase.validate();
        assertTrue(liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).isEmpty());
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement();
            var rows =
                sql.executeQuery(
                    "SELECT MD5SUM FROM DATABASECHANGELOG WHERE ID='20260910-chat-recovery-policy'")) {
          assertTrue(rows.next());
          assertNotNull(rows.getString(1));
          assertFalse(rows.next());
        }
        try (var sql = c.createStatement();
            var rows =
                sql.executeQuery(
                    "SELECT chat_recovery_mode, chat_recovery_policy_revision FROM `user` WHERE id='enrolled'")) {
          assertTrue(rows.next());
          assertEquals("LOGIN_PASSWORD", rows.getString(1));
          assertEquals(7, rows.getLong(2));
        }
      }
    }
  }
}
