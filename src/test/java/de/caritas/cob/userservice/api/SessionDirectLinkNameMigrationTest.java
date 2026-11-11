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
 * Runs the ORISO-Frontend#2170 changeset on session: existing conversations keep no link name, a
 * direct-link conversation can carry one, and a rollback keeps the column and its names, also when
 * the column already existed and the changeset was only marked as ran.
 */
class SessionDirectLinkNameMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261110_session_direct_link_name_2170/changeSet.xml";

  @Test
  void update_Should_AddANullableName_And_RollbackKeepsTheNames() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-link-name-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
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

        assertThat(count(c, "id = 500 AND direct_link_name IS NULL")).isEqualTo(1);
        try (var sql = c.createStatement()) {
          // The longest name a link may have (CounsellorDirectLinkService.NAME_MAX).
          sql.execute(
              "INSERT INTO session (id, postcode, direct_link_name) VALUES (501, '1', '"
                  + "x".repeat(100)
                  + "')");
        }
        // Liquibase runs the connection without auto-commit; keep the row like production would.
        if (!c.getAutoCommit()) {
          c.commit();
        }
        assertThat(count(c, "CHAR_LENGTH(direct_link_name) = 100")).isEqualTo(1);

        liquibase.rollback(1, (String) null);
        assertThat(count(c, "CHAR_LENGTH(direct_link_name) = 100"))
            .as("the additive column and its names survive the rollback")
            .isEqualTo(1);
        assertThat(count(c, "1 = 1")).as("conversations survive the rollback").isEqualTo(2);
      }
    }
  }

  /** The master changelog's rule: safe on a database that already received the column by hand. */
  @Test
  void update_Should_MarkRanAndKeepTheData_When_TheColumnIsAlreadyThere() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:direct-link-name-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, postcode VARCHAR(5) NOT NULL,"
                + " direct_link_name VARCHAR(100), PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO session (id, postcode, direct_link_name) VALUES (500, '1', 'Flyer')");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "direct_link_name = 'Flyer'")).isEqualTo(1);
        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT EXECTYPE FROM DATABASECHANGELOG"
                        + " WHERE ID = '20261110-session-direct-link-name-2170'")) {
          rs.next();
          assertThat(rs.getString(1)).isEqualTo("MARK_RAN");
        }

        // Liquibase runs the rollback of a MARK_RAN changeset too; it must not drop the names.
        liquibase.rollback(1, (String) null);
        assertThat(count(c, "direct_link_name = 'Flyer'")).isEqualTo(1);
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
