package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
 * The stored dashboard stands: one row per cutoff, and every snapshot setting starts with an
 * introduction line, so it has a "valid since" date from the start.
 */
class StatisticsDashboardSnapshotMigrationTest {

  private static final String SETTINGS_TABLE_CHANGELOG =
      "db/changelog/changeset/20261101_statistics_setting_change_1535/changeSet.xml";
  private static final String CHANGELOG =
      "db/changelog/changeset/20261102_statistics_dashboard_snapshot_1547/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangesetAfterTheSettingsTable() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG);
    assertThat(master.indexOf(CHANGELOG)).isGreaterThan(master.indexOf(SETTINGS_TABLE_CHANGELOG));
  }

  @Test
  void update_Should_CreateTheStandTableWithIntroductionLines_And_RollbackRemovesBoth()
      throws Exception {
    var url =
        "jdbc:h2:mem:stats-snapshot-mig-" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1";
    try (var table = new Liquibase(SETTINGS_TABLE_CHANGELOG, accessor(), database(url))) {
      table.update(new Contexts(), new LabelExpression());
    }

    try (var snapshot = new Liquibase(CHANGELOG, accessor(), database(url));
        Connection c = DriverManager.getConnection(url, "sa", "")) {
      snapshot.update(new Contexts(), new LabelExpression());

      assertThat(
              rows(
                  c,
                  "SELECT setting_key || '=' || new_value FROM statistics_setting_change"
                      + " WHERE setting_key LIKE '%SnapshotInterval' AND old_value IS NULL"
                      + " AND changed_by_id IS NULL AND effective_from IS NOT NULL ORDER BY id"))
          .containsExactly(
              "agencySnapshotInterval=DAILY",
              "tenantSnapshotInterval=DAILY",
              "platformSnapshotInterval=DAILY");

      insertStand(c);
      assertThatThrownBy(() -> insertStand(c)).isInstanceOf(SQLException.class);

      snapshot.rollback(1, (String) null);
      assertThat(
              rows(
                  c,
                  "SELECT table_name FROM information_schema.tables"
                      + " WHERE table_name = 'STATISTICS_DASHBOARD_SNAPSHOT'"))
          .isEmpty();
      assertThat(
              rows(
                  c,
                  "SELECT setting_key FROM statistics_setting_change"
                      + " WHERE setting_key LIKE '%SnapshotInterval'"))
          .isEmpty();
    }
  }

  private static void insertStand(Connection c) throws SQLException {
    try (var st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO statistics_dashboard_snapshot"
              + " (scope, tenant_id, snapshot_interval, cutoff_at, computed_at, payload) VALUES"
              + " ('TENANT', 5, 'DAILY', '2026-11-04 05:00:00', '2026-11-04 05:00:03', '{}')");
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
