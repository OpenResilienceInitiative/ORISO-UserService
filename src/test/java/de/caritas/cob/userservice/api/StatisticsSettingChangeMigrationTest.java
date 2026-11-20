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
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The statistics settings protocol: created by the changeset with one introduction line per setting
 * (so every setting has a "valid since" date from day one), rollback drops it.
 */
class StatisticsSettingChangeMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261101_statistics_setting_change_1535/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangeset() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG);
  }

  @Test
  void update_Should_CreateTheTableWithIntroductionLines_And_RollbackDropsIt() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:stats-settings-mig-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(
                rows(
                    c,
                    "SELECT setting_key || '=' || new_value FROM statistics_setting_change"
                        + " WHERE old_value IS NULL AND changed_by_id IS NULL"
                        + " AND effective_from IS NOT NULL ORDER BY id"))
            .containsExactly(
                "agencyMinimumCounsellors=5",
                "tenantMinimumCounsellors=5",
                "platformMinimumCounsellors=5",
                "messageCountTracking=ENABLED");

        liquibase.rollback(1, (String) null);
        assertThat(
                rows(
                    c,
                    "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_name = 'STATISTICS_SETTING_CHANGE'"))
            .isEmpty();
      }
    }
  }

  private static List<String> rows(Connection c, String sql) throws Exception {
    var result = new ArrayList<String>();
    try (var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      while (rs.next()) {
        result.add(rs.getString(1));
      }
    }
    return result;
  }
}
