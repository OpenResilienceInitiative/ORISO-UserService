package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The dashboard time windows (UserService#1544) get one introduction line each in the statistics
 * settings protocol, so both have a "valid since" date from day one; rollback removes only those.
 */
class StatisticsTimeWindowsMigrationTest {

  private static final String SETTINGS_TABLE_CHANGELOG =
      "db/changelog/changeset/20261101_statistics_setting_change_1535/changeSet.xml";
  private static final String CHANGELOG =
      "db/changelog/changeset/20261101_statistics_time_windows_1544/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangesetAfterTheSettingsTable() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG);
    assertThat(master.indexOf(CHANGELOG)).isGreaterThan(master.indexOf(SETTINGS_TABLE_CHANGELOG));
  }

  @Test
  void update_Should_AddTodaysWindowsAsIntroductionLines_And_RollbackRemovesOnlyThose()
      throws Exception {
    // Each Liquibase run closes its connection; the in-memory database outlives them.
    var url =
        "jdbc:h2:mem:stats-windows-mig-" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1";
    try (var table = new Liquibase(SETTINGS_TABLE_CHANGELOG, accessor(), database(url))) {
      table.update(new Contexts(), new LabelExpression());
    }
    try (var liquibase = new Liquibase(CHANGELOG, accessor(), database(url))) {
      liquibase.update(new Contexts(), new LabelExpression());

      assertThat(introductionLines(url))
          .containsExactly(
              "agencyMinimumCounsellors=5",
              "tenantMinimumCounsellors=5",
              "platformMinimumCounsellors=5",
              "messageCountTracking=ENABLED",
              "requestChartWeeks=5",
              "topicMonths=4");

      liquibase.rollback(1, (String) null);
      assertThat(introductionLines(url))
          .containsExactly(
              "agencyMinimumCounsellors=5",
              "tenantMinimumCounsellors=5",
              "platformMinimumCounsellors=5",
              "messageCountTracking=ENABLED");
    }
  }

  private static ClassLoaderResourceAccessor accessor() {
    return new ClassLoaderResourceAccessor();
  }

  private static Database database(String url) throws Exception {
    return DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(
            new JdbcConnection(DriverManager.getConnection(url, "sa", "")));
  }

  private static List<String> introductionLines(String url) throws Exception {
    var result = new ArrayList<String>();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT setting_key || '=' || new_value FROM statistics_setting_change"
                    + " WHERE old_value IS NULL AND changed_by_id IS NULL"
                    + " AND effective_from IS NOT NULL ORDER BY id")) {
      while (rs.next()) {
        result.add(rs.getString(1));
      }
    }
    return result;
  }
}
