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
 * Runs the #1662 changeset: every direct link and call share link gets the guest account mode it
 * behaves with today, a Gesprächskreis starts as "guests choose", and a rerun or a rollback keeps
 * the data.
 */
class GuestAccountModeMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261117_guest_account_mode_1662/changeSet.xml";

  @Test
  void update_Should_KeepTodaysBehaviourOfEveryLink_And_LetGroupGuestsChoose() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:guest-account-mode-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute(
            "CREATE TABLE agency_invite_link (id BIGINT NOT NULL, link_kind VARCHAR(32) NOT NULL,"
                + " temporary_account BOOLEAN DEFAULT FALSE NOT NULL, access_mode VARCHAR(16),"
                + " PRIMARY KEY (id))");
        sql.execute(
            "INSERT INTO agency_invite_link (id, link_kind, temporary_account, access_mode) VALUES"
                + " (1, 'COUNSELLOR', TRUE, 'GUESTS_ONLY'),"
                + " (2, 'COUNSELLOR', TRUE, 'BOTH'),"
                + " (3, 'COUNSELLOR', FALSE, 'ACCOUNTS_ONLY'),"
                + " (4, 'COUNSELLOR', TRUE, NULL),"
                + " (5, 'COUNSELLOR', FALSE, NULL),"
                + " (6, 'CALL', TRUE, NULL),"
                + " (7, 'CALL', FALSE, NULL),"
                + " (8, 'TENANT', FALSE, NULL)");
        sql.execute("CREATE TABLE chat (id BIGINT NOT NULL, PRIMARY KEY (id))");
        sql.execute("INSERT INTO chat (id) VALUES (10)");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(modeOfLink(c, 1)).isEqualTo("TEMPORARY");
        assertThat(modeOfLink(c, 2)).isEqualTo("GUEST_CHOOSES");
        assertThat(modeOfLink(c, 3)).isEqualTo("PERMANENT");
        // A direct link without an access mode reads as #2252 reads it (AgencyInviteLink).
        assertThat(modeOfLink(c, 4)).isEqualTo("GUEST_CHOOSES");
        assertThat(modeOfLink(c, 5)).isEqualTo("PERMANENT");
        // A call link required a temporary registration exactly when its switch was on.
        assertThat(modeOfLink(c, 6)).isEqualTo("TEMPORARY");
        assertThat(modeOfLink(c, 7)).isEqualTo("PERMANENT");
        assertThat(modeOfLink(c, 8)).as("other links create no guests").isNull();
        assertThat(modeOfChat(c, 10)).isEqualTo("GUEST_CHOOSES");
        try (var sql = c.createStatement()) {
          sql.execute("INSERT INTO chat (id) VALUES (11)");
        }
        assertThat(modeOfChat(c, 11))
            .as("a new Gesprächskreis lets guests choose")
            .isEqualTo("GUEST_CHOOSES");

        // A rerun on a database that already has the columns changes nothing.
        liquibase.update(new Contexts(), new LabelExpression());
        liquibase.rollback(10, (String) null);
        assertThat(modeOfLink(c, 1))
            .as("the additive columns survive a rollback")
            .isEqualTo("TEMPORARY");
      }
    }
  }

  private static String modeOfLink(Connection c, long id) throws Exception {
    return single(c, "SELECT guest_account_mode FROM agency_invite_link WHERE id = " + id);
  }

  private static String modeOfChat(Connection c, long id) throws Exception {
    return single(c, "SELECT guest_account_mode FROM chat WHERE id = " + id);
  }

  private static String single(Connection c, String query) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery(query)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
