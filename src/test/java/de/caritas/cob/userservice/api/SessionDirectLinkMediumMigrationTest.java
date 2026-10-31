package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Runs the FE#1952 changeset on session: existing conversations keep no origin (they stay "Mail"),
 * a direct-link conversation can carry its medium, and the rollback removes the column.
 */
class SessionDirectLinkMediumMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261030_session_direct_link_medium_1952/changeSet.xml";

  @Test
  void update_Should_AddANullableMedium_And_RollbackRemovesIt() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-link-medium-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, postcode VARCHAR(5) NOT NULL,"
                + " PRIMARY KEY (id))");
        sql.execute("INSERT INTO session (id, postcode) VALUES (500, '12345')");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "id = 500 AND direct_link_medium IS NULL")).isEqualTo(1);
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO session (id, postcode, direct_link_medium) VALUES (501, '1', 'VIDEO')");
        }
        // Liquibase runs the connection without auto-commit; keep the row like production would.
        if (!c.getAutoCommit()) {
          c.commit();
        }
        assertThat(count(c, "direct_link_medium = 'VIDEO'")).isEqualTo(1);

        liquibase.rollback(1, (String) null);
        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SESSION'"
                        + " AND COLUMN_NAME = 'DIRECT_LINK_MEDIUM'")) {
          rs.next();
          assertThat(rs.getInt(1)).isZero();
        }
        assertThat(count(c, "1 = 1")).as("conversations survive the rollback").isEqualTo(2);
      }
    }
  }

  /** The master changelog's rule: safe on a database that already received the column by hand. */
  @Test
  void update_Should_MarkRanAndKeepTheData_When_TheColumnIsAlreadyThere() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-link-medium-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, postcode VARCHAR(5) NOT NULL,"
                + " direct_link_medium VARCHAR(16), PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO session (id, postcode, direct_link_medium) VALUES (500, '1', 'AUDIO')");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "direct_link_medium = 'AUDIO'")).isEqualTo(1);
        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT EXECTYPE FROM DATABASECHANGELOG"
                        + " WHERE ID = '20261030-session-direct-link-medium-1952'")) {
          rs.next();
          assertThat(rs.getString(1)).isEqualTo("MARK_RAN");
        }
      }
    }
  }

  private static int count(Connection c, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM session WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
