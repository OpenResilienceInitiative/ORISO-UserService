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
 * #1662: the mode a temporary guest entered under is stored on the account, nullable, and existing
 * accounts stay untouched (they are judged by their link or circle).
 */
class GuestAccountModeAtEntryMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261117_guest_account_mode_at_entry_1662/changeSet.xml";

  @Test
  void update_Should_AddANullableEntryModeAndLeaveExistingAccountsAlone() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:guest-mode-at-entry-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE `user` (user_id VARCHAR(36) NOT NULL, PRIMARY KEY (user_id))");
        sql.execute("INSERT INTO `user` (user_id) VALUES ('existing')");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
        // a rerun is harmless
        liquibase.update(new Contexts(), new LabelExpression());
        // Liquibase closes the connection with itself: read while it is open.
        var sql = c.createStatement();
        var rows = sql.executeQuery("SELECT guest_account_mode_at_entry FROM `user`");
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isNull();
        sql.execute(
            "UPDATE `user` SET guest_account_mode_at_entry = 'GUEST_CHOOSES' WHERE user_id ="
                + " 'existing'");
      }
    }
  }
}
