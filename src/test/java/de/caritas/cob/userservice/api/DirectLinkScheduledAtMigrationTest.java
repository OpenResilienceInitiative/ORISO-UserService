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
 * Runs the ORISO-Frontend#2142 changeset on agency_invite_link: existing links keep no date, a
 * direct link can carry one, and a rollback keeps the column and its dates, also when the column
 * already existed and the changeset was only marked as ran.
 */
class DirectLinkScheduledAtMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261112_direct_link_scheduled_at_2142/changeSet.xml";

  @Test
  void update_Should_AddANullableDate_And_RollbackKeepsTheDates() throws Exception {
    try (Connection c = connection()) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE agency_invite_link (id BIGINT NOT NULL, PRIMARY KEY (id))");
        sql.execute("INSERT INTO agency_invite_link (id) VALUES (500)");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "id = 500 AND scheduled_at IS NULL")).isEqualTo(1);
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO agency_invite_link (id, scheduled_at)"
                  + " VALUES (501, '2026-11-20 13:30:00')");
        }
        if (!c.getAutoCommit()) {
          c.commit();
        }

        liquibase.rollback(1, (String) null);
        assertThat(count(c, "scheduled_at IS NOT NULL"))
            .as("the additive column and its dates survive the rollback")
            .isEqualTo(1);
      }
    }
  }

  @Test
  void update_Should_MarkRanAndKeepTheData_When_TheColumnIsAlreadyThere() throws Exception {
    try (Connection c = connection()) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, scheduled_at DATETIME,"
                + " PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO agency_invite_link (id, scheduled_at) VALUES (500, '2026-11-20 13:30:00')");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT EXECTYPE FROM DATABASECHANGELOG"
                        + " WHERE ID = '20261112-direct-link-scheduled-at-2142'")) {
          rs.next();
          assertThat(rs.getString(1)).isEqualTo("MARK_RAN");
        }
        liquibase.rollback(1, (String) null);
        assertThat(count(c, "scheduled_at IS NOT NULL")).isEqualTo(1);
      }
    }
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:direct-link-scheduled-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  private static int count(Connection c, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM agency_invite_link WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
