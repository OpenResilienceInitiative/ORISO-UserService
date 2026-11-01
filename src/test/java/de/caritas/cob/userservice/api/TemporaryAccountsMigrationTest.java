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
 * Runs the temporary-account changesets (#1476): the settings row starts with the ADR-029 defaults,
 * existing users get empty snapshot columns, and the rollback removes both.
 */
class TemporaryAccountsMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261028_temporary_accounts_1476/changeSet.xml";

  @Test
  void update_Should_SeedTheDefaults_And_KeepExistingUsers_And_RollBack() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:temporary-accounts-"
                + UUID.randomUUID()
                + ";MODE=MariaDB;NON_KEYWORDS=USER",
            "sa",
            "")) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE user (user_id VARCHAR(36) NOT NULL, temporary_account BIT DEFAULT"
                + " FALSE NOT NULL, PRIMARY KEY (user_id))");
        sql.execute("INSERT INTO user (user_id, temporary_account) VALUES ('old', TRUE)");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(
                single(
                    c,
                    "SELECT COUNT(*) FROM temporary_account_settings WHERE id = 1"
                        + " AND call_delete_after_minutes = 15 AND call_max_lifetime_hours = 24"
                        + " AND chat_inactivity_days = 30 AND hide_password_field = FALSE"
                        + " AND message_retention = 'GUEST_DECIDES' AND revision = 0"))
            .isEqualTo(1);
        assertThat(
                single(
                    c,
                    "SELECT COUNT(*) FROM user WHERE user_id = 'old'"
                        + " AND temporary_medium IS NULL AND temporary_deleted_at IS NULL"
                        + " AND temporary_deletion_started_at IS NULL"
                        + " AND temporary_redact_id IS NULL"))
            .isEqualTo(1);
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO user (user_id, temporary_account, temporary_medium,"
                  + " temporary_invite_link_id, temporary_call_delete_after_minutes,"
                  + " temporary_call_max_lifetime_hours, temporary_chat_inactivity_days,"
                  + " temporary_message_retention, temporary_keep_messages)"
                  + " VALUES ('guest', TRUE, 'VIDEO', 9, 15, 24, 30, 'GUEST_DECIDES', NULL)");
        }
        assertThat(single(c, "SELECT COUNT(*) FROM user WHERE temporary_medium = 'VIDEO'"))
            .isEqualTo(1);

        liquibase.rollback(3, (String) null);
        assertThat(
                single(
                    c,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE TABLE_NAME = 'TEMPORARY_ACCOUNT_SETTINGS'"))
            .isZero();
        assertThat(
                single(
                    c,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'USER'"
                        + " AND COLUMN_NAME LIKE 'TEMPORARY_%' AND COLUMN_NAME <>"
                        + " 'TEMPORARY_ACCOUNT'"))
            .isZero();
        assertThat(single(c, "SELECT COUNT(*) FROM user WHERE user_id = 'old'")).isEqualTo(1);
      }
    }
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  private static int single(Connection c, String query) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery(query)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
