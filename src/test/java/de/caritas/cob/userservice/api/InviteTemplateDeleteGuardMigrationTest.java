package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/** Executes the actual changeset against real SQL tables, including rollback and orphan failure. */
class InviteTemplateDeleteGuardMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261013_invite_template_delete_guard/changeSet.xml";

  @Test
  void guardProtectsQueueInBothDirectionsAndKeepsHistoricalDelivery() throws Exception {
    try (Connection connection = database();
        var migration = liquibase(connection)) {
      createTables(connection);
      execute(connection, "INSERT INTO invite_email_template VALUES (1)");
      execute(connection, "INSERT INTO account_invite VALUES (1, 1)");
      execute(connection, "INSERT INTO invite_email_delivery VALUES (1, 1, 'Sent body')");
      migration.update(new Contexts(), new LabelExpression());

      assertThatThrownBy(() -> execute(connection, "DELETE FROM invite_email_template WHERE id=1"))
          .isInstanceOf(SQLException.class);
      assertThat(count(connection, "invite_email_template")).isEqualTo(1);
      assertThat(count(connection, "account_invite")).isEqualTo(1);
      execute(connection, "UPDATE account_invite SET queued_template_id=NULL WHERE id=1");
      execute(connection, "DELETE FROM invite_email_template WHERE id=1");

      assertThatThrownBy(() -> execute(connection, "INSERT INTO account_invite VALUES (2, 1)"))
          .isInstanceOf(SQLException.class);
      assertThat(count(connection, "account_invite")).isEqualTo(1);
      try (var sql = connection.createStatement();
          var history =
              sql.executeQuery("SELECT template_id, body_snapshot FROM invite_email_delivery")) {
        assertThat(history.next()).isTrue();
        assertThat(history.getLong(1)).isEqualTo(1L);
        assertThat(history.getString(2)).isEqualTo("Sent body");
      }
    }
  }

  @Test
  void migrationHaltsOnOrphanWithoutChangingIt() throws Exception {
    try (Connection connection = database();
        var migration = liquibase(connection)) {
      createTables(connection);
      execute(connection, "INSERT INTO account_invite VALUES (1, 99)");

      assertThatThrownBy(() -> migration.update(new Contexts(), new LabelExpression()))
          .hasStackTraceContaining("Queued invitation template references are orphaned");

      assertThat(count(connection, "account_invite")).isEqualTo(1);
      assertThat(count(connection, "invite_email_template")).isZero();
      try (var sql = connection.createStatement();
          var invite =
              sql.executeQuery("SELECT queued_template_id FROM account_invite WHERE id=1")) {
        assertThat(invite.next()).isTrue();
        assertThat(invite.getLong(1)).isEqualTo(99L);
      }
    }
  }

  @Test
  void rollbackRemovesOnlyTheConstraint() throws Exception {
    try (Connection connection = database();
        var migration = liquibase(connection)) {
      createTables(connection);
      execute(connection, "INSERT INTO invite_email_template VALUES (1)");
      execute(connection, "INSERT INTO account_invite VALUES (1, 1)");
      execute(connection, "INSERT INTO invite_email_delivery VALUES (1, 1, 'Sent body')");
      migration.update(new Contexts(), new LabelExpression());

      migration.rollback(1, new Contexts(), new LabelExpression());

      assertThat(count(connection, "invite_email_template")).isEqualTo(1);
      assertThat(count(connection, "account_invite")).isEqualTo(1);
      assertThat(count(connection, "invite_email_delivery")).isEqualTo(1);
      execute(connection, "DELETE FROM invite_email_template WHERE id=1");
    }
  }

  private static Connection database() throws Exception {
    String url = System.getenv("TEMPLATE_DELETE_IT_DB_URL");
    if (url != null) {
      String user = System.getenv("TEMPLATE_DELETE_IT_DB_USER");
      String password = System.getenv("TEMPLATE_DELETE_IT_DB_PASSWORD");
      // Validate before connecting, not just before resetting tables: this fixture is destructive.
      String validatedUrl = validateExternalSettings(url, user, password);
      Connection connection = DriverManager.getConnection(validatedUrl, user, password);
      execute(connection, "DROP TABLE IF EXISTS account_invite");
      execute(connection, "DROP TABLE IF EXISTS invite_email_template");
      execute(connection, "DROP TABLE IF EXISTS invite_email_delivery");
      execute(connection, "DROP TABLE IF EXISTS DATABASECHANGELOG");
      execute(connection, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
      return connection;
    }
    return DriverManager.getConnection(
        "jdbc:h2:mem:template-delete-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
  }

  static String validateExternalSettings(String url, String user, String password) {
    if (url == null || !url.startsWith("jdbc:")) {
      throw unsafeExternalDatabase();
    }
    URI database;
    try {
      database = URI.create(url.substring("jdbc:".length()));
    } catch (IllegalArgumentException invalidUri) {
      throw unsafeExternalDatabase();
    }
    if (!"mariadb".equals(database.getScheme())
        || !("localhost".equals(database.getHost()) || "127.0.0.1".equals(database.getHost()))
        || !"/template_delete_test".equals(database.getRawPath())
        || database.getRawUserInfo() != null
        || database.getRawQuery() != null
        || database.getRawFragment() != null
        || (database.getPort() != -1 && (database.getPort() < 1 || database.getPort() > 65535))) {
      throw unsafeExternalDatabase();
    }
    if (user == null || user.isBlank() || password == null || password.isBlank()) {
      throw new IllegalArgumentException(
          "Set TEMPLATE_DELETE_IT_DB_USER and TEMPLATE_DELETE_IT_DB_PASSWORD for the disposable database");
    }
    return url;
  }

  private static IllegalArgumentException unsafeExternalDatabase() {
    // Do not include the untrusted URL: it might contain embedded credentials.
    return new IllegalArgumentException(
        "TEMPLATE_DELETE_IT_DB_URL must name MariaDB schema template_delete_test on localhost or 127.0.0.1 without credentials or URL options");
  }

  private static Liquibase liquibase(Connection connection) throws Exception {
    return new Liquibase(
        CHANGELOG,
        new ClassLoaderResourceAccessor(),
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
  }

  private static void createTables(Connection connection) throws Exception {
    execute(connection, "CREATE TABLE invite_email_template (id BIGINT PRIMARY KEY)");
    execute(
        connection,
        "CREATE TABLE account_invite (id BIGINT PRIMARY KEY, queued_template_id BIGINT NULL)");
    execute(
        connection,
        "CREATE TABLE invite_email_delivery (id BIGINT PRIMARY KEY, template_id BIGINT NULL, body_snapshot VARCHAR(255))");
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long count(Connection connection, String table) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
