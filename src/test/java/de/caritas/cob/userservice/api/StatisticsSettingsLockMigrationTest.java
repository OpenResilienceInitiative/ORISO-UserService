package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The lock row a save of the statistics settings queues on (UserService#1562). */
class StatisticsSettingsLockMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261102_statistics_settings_lock_1562/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheLockChangeset() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG);
  }

  @Test
  void update_Should_CreateExactlyOneLockRow_And_RollbackRemovesTheTable() throws Exception {
    var url = newDatabaseUrl();

    try (var liquibase = liquibase(url);
        var c = DriverManager.getConnection(url, "sa", "");
        var st = c.createStatement()) {
      liquibase.update(new Contexts(), new LabelExpression());

      assertThat(lockRows(st)).containsExactly(1L);
      assertThatThrownBy(() -> st.executeUpdate("INSERT INTO statistics_settings_lock VALUES (1)"))
          .isInstanceOf(SQLException.class);

      liquibase.rollback(2, (String) null);
      try (var rs =
          st.executeQuery(
              "SELECT table_name FROM information_schema.tables"
                  + " WHERE table_name = 'STATISTICS_SETTINGS_LOCK'")) {
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void update_Should_AddOnlyTheRow_When_TheTableWasCreatedByHand() throws Exception {
    var url = newDatabaseUrl();

    try (var liquibase = liquibase(url);
        var c = DriverManager.getConnection(url, "sa", "");
        var st = c.createStatement()) {
      st.executeUpdate("CREATE TABLE statistics_settings_lock (id BIGINT NOT NULL PRIMARY KEY)");

      liquibase.update(new Contexts(), new LabelExpression());

      assertThat(lockRows(st)).containsExactly(1L);
    }
  }

  @Test
  void update_Should_ChangeNothing_When_TableAndRowWereCreatedByHand() throws Exception {
    var url = newDatabaseUrl();

    try (var liquibase = liquibase(url);
        var c = DriverManager.getConnection(url, "sa", "");
        var st = c.createStatement()) {
      st.executeUpdate("CREATE TABLE statistics_settings_lock (id BIGINT NOT NULL PRIMARY KEY)");
      st.executeUpdate("INSERT INTO statistics_settings_lock VALUES (1)");

      liquibase.update(new Contexts(), new LabelExpression());

      assertThat(lockRows(st)).containsExactly(1L);
    }
  }

  private static String newDatabaseUrl() {
    return "jdbc:h2:mem:stats-lock-mig-" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1";
  }

  private static Liquibase liquibase(String url) throws Exception {
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(
                new JdbcConnection(DriverManager.getConnection(url, "sa", "")));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  private static ArrayList<Long> lockRows(Statement st) throws SQLException {
    var ids = new ArrayList<Long>();
    try (var rs = st.executeQuery("SELECT id FROM statistics_settings_lock")) {
      while (rs.next()) {
        ids.add(rs.getLong(1));
      }
    }
    return ids;
  }
}
