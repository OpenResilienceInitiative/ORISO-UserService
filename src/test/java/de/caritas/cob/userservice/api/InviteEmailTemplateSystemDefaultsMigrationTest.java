package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * Runs the system-default changesets on the invite_email_template table as it stands after 0055 and
 * 20260924: the flag column arrives with a default, exactly one active platform default per kind
 * and language is seeded, nothing is duplicated, and the rollback removes both again.
 */
class InviteEmailTemplateSystemDefaultsMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261001_invite_email_template_system_default/changeSet.xml";

  @Test
  void update_Should_SeedOneActivePlatformDefaultPerKindAndLanguage() throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      insertOldRow(c);
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(systemDefaults(c))
            .containsExactlyInAnyOrder(
                "COUNSELLOR_INVITE/de tenant=null active=true",
                "COUNSELLOR_INVITE/en tenant=null active=true",
                "TENANT_INVITE/de tenant=null active=true",
                "TENANT_INVITE/en tenant=null active=true");
        // The row that existed before gets the column default: not a system default.
        assertThat(count(c, "system_default = FALSE")).isEqualTo(1);
        assertThat(
                count(
                    c,
                    "system_default = TRUE AND (TRIM(subject) = '' OR TRIM(body) = ''"
                        + " OR body LIKE '%{{inviteLink}}%')"))
            .as("defaults carry text, and the layout owns the action link")
            .isZero();
      }
    }
  }

  @Test
  void update_Should_NotDuplicate_When_ADefaultForThatKindAndLanguageExists() throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "ALTER TABLE invite_email_template ADD COLUMN system_default BIT NOT NULL DEFAULT 0");
        sql.execute(
            "INSERT INTO invite_email_template (kind, name, language, subject, body, active,"
                + " create_date, system_default) VALUES ('COUNSELLOR_INVITE', 'Edited', 'de',"
                + " 'Edited subject', 'Edited body', TRUE, CURRENT_TIMESTAMP, TRUE)");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(systemDefaults(c)).hasSize(4);
        assertThat(count(c, "system_default = TRUE AND name = 'Edited'")).isEqualTo(1);
      }
    }
  }

  @Test
  void rollback_Should_RemoveTheSeededDefaultsAndTheColumn() throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      insertOldRow(c);
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        liquibase.rollback(2, new Contexts(), new LabelExpression());

        assertThat(count(c, "1 = 1")).isEqualTo(1);
        try (var rs =
            c.getMetaData().getColumns(null, null, "INVITE_EMAIL_TEMPLATE", "SYSTEM_DEFAULT")) {
          assertThat(rs.next()).isFalse();
        }
      }
    }
  }

  @Test
  void rollback_Should_KeepADefaultItDidNotSeed() throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "ALTER TABLE invite_email_template ADD COLUMN system_default BIT NOT NULL DEFAULT 0");
        sql.execute(
            "INSERT INTO invite_email_template (kind, name, language, subject, body, active,"
                + " create_date, system_default) VALUES ('COUNSELLOR_INVITE', 'Edited', 'de',"
                + " 'Edited subject', 'Edited body', TRUE, CURRENT_TIMESTAMP, TRUE)");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        liquibase.rollback(2, new Contexts(), new LabelExpression());

        // The seed skipped this row, so the rollback must not take it with the seeded ones.
        assertThat(count(c, "1 = 1")).isEqualTo(1);
        assertThat(count(c, "name = 'Edited'")).isEqualTo(1);
      }
    }
  }

  private static Connection newDatabase() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:invite-template-defaults-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  /** 0055_account_invites plus 20260924_invite_email_template_tenant. */
  private static void createTableAsBefore(Connection c) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE invite_email_template ("
              + " id BIGINT NOT NULL AUTO_INCREMENT,"
              + " kind VARCHAR(32) NOT NULL,"
              + " name VARCHAR(255) NOT NULL,"
              + " language VARCHAR(16) NULL,"
              + " subject VARCHAR(255) NOT NULL,"
              + " body LONGTEXT NOT NULL,"
              + " active BIT NOT NULL DEFAULT 1,"
              + " created_by_user_id VARCHAR(36) NULL,"
              + " create_date DATETIME NOT NULL,"
              + " update_date DATETIME NULL,"
              + " tenant_id BIGINT NULL,"
              + " PRIMARY KEY (id))");
    }
  }

  private static void insertOldRow(Connection c) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute(
          "INSERT INTO invite_email_template (kind, name, language, subject, body, active,"
              + " create_date) VALUES ('COUNSELLOR_INVITE', 'Old', 'de', 'S', 'B', TRUE,"
              + " CURRENT_TIMESTAMP)");
    }
  }

  private static List<String> systemDefaults(Connection c) throws Exception {
    List<String> rows = new ArrayList<>();
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT kind, language, tenant_id, active FROM invite_email_template"
                    + " WHERE system_default = TRUE")) {
      while (rs.next()) {
        rows.add(
            rs.getString("kind")
                + "/"
                + rs.getString("language")
                + " tenant="
                + rs.getObject("tenant_id")
                + " active="
                + rs.getBoolean("active"));
      }
    }
    return rows;
  }

  private static long count(Connection c, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM invite_email_template WHERE " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
