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

/** The working hours table (ADR-032, addendum v1.1): created by its changeset, dropped again. */
class CalendarWorkingHoursMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261114_calendar_working_hours_1000/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangeset_AfterTheBlockerTable() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master.indexOf(CHANGELOG))
        .isGreaterThan(master.indexOf("20261114_calendar_blocker_1000/changeSet.xml"));
  }

  @Test
  void update_Should_CreateTheTable_And_RollbackDropsIt() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:calendar-working-hours-" + UUID.randomUUID() + ";MODE=MariaDB",
            "sa",
            "")) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var st = c.createStatement()) {
          st.execute(
              "INSERT INTO calendar_working_hours (id, consultant_id, weekday, start_time,"
                  + " end_time) VALUES ('"
                  + UUID.randomUUID()
                  + "', 'synthetic-consultant', 1, '07:00:00', '20:00:00')");
        }
        assertThat(columns(c))
            .containsExactlyInAnyOrder("ID", "CONSULTANT_ID", "WEEKDAY", "START_TIME", "END_TIME");

        liquibase.rollback(1, (String) null);
        assertThat(columns(c)).isEmpty();
      }
    }
  }

  private static List<String> columns(Connection c) throws Exception {
    var names = new ArrayList<String>();
    try (var rs = c.getMetaData().getColumns(null, null, "CALENDAR_WORKING_HOURS", null)) {
      while (rs.next()) {
        names.add(rs.getString("COLUMN_NAME"));
      }
    }
    return names;
  }
}
