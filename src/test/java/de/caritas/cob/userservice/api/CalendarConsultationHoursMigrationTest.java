package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Element;

/**
 * Sprechstunden and the booking settings of a counsellor (ORISO-Frontend#2287): both tables are
 * created by their changesets, a booking setting row gets its defaults, and rollback drops them.
 */
class CalendarConsultationHoursMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261117_calendar_consultation_hours_2287/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangeset_AfterTheWorkingHours() throws Exception {
    var includes = activeIncludes("db/changelog/userservice-master.xml");

    var workingHours =
        includes.indexOf(
            "db/changelog/changeset/20261114_calendar_working_hours_exceptions_1000/changeSet.xml");
    var consultationHours = includes.indexOf(CHANGELOG);

    assertThat(workingHours).as("working hours changeset included").isNotNegative();
    assertThat(consultationHours)
        .as("consultation hours changeset included")
        .isGreaterThan(workingHours);
  }

  @Test
  void update_Should_CreateBothTables_WithDefaults_And_RollbackDropsThem() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:calendar-consultation-hours-" + UUID.randomUUID() + ";MODE=MariaDB",
            "sa",
            "")) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var st = c.createStatement()) {
          st.execute(
              "INSERT INTO calendar_consultation_hours (id, consultant_id, weekday, start_time,"
                  + " end_time) VALUES ('"
                  + UUID.randomUUID()
                  + "', 'synthetic-consultant', 2, '09:00:00', '12:00:00')");
          st.execute(
              "INSERT INTO calendar_booking_setting (consultant_id) VALUES"
                  + " ('synthetic-consultant')");
          try (var rs =
              st.executeQuery(
                  "SELECT auto_accept, notice_days, slot_minutes FROM calendar_booking_setting")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("auto_accept")).isTrue();
            assertThat(rs.getInt("notice_days")).isEqualTo(1);
            assertThat(rs.getInt("slot_minutes")).isEqualTo(60);
          }
        }
        assertThat(columns(c, "CALENDAR_CONSULTATION_HOURS"))
            .containsExactlyInAnyOrder("ID", "CONSULTANT_ID", "WEEKDAY", "START_TIME", "END_TIME");
        assertThat(columns(c, "CALENDAR_BOOKING_SETTING"))
            .containsExactlyInAnyOrder(
                "CONSULTANT_ID", "AUTO_ACCEPT", "NOTICE_DAYS", "SLOT_MINUTES");

        // The setting table, the index and the hours table.
        liquibase.rollback(3, (String) null);
        assertThat(columns(c, "CALENDAR_CONSULTATION_HOURS")).isEmpty();
        assertThat(columns(c, "CALENDAR_BOOKING_SETTING")).isEmpty();
      }
    }
  }

  private static List<String> activeIncludes(String changelog) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setNamespaceAware(true);
    try (var xml = new ClassPathResource(changelog).getInputStream()) {
      var elements = factory.newDocumentBuilder().parse(xml).getElementsByTagNameNS("*", "include");
      var files = new ArrayList<String>();
      for (int index = 0; index < elements.getLength(); index++) {
        files.add(((Element) elements.item(index)).getAttribute("file"));
      }
      return files;
    }
  }

  private static List<String> columns(Connection c, String table) throws Exception {
    var names = new ArrayList<String>();
    try (var rs = c.getMetaData().getColumns(null, null, table, null)) {
      while (rs.next()) {
        names.add(rs.getString("COLUMN_NAME"));
      }
    }
    return names;
  }
}
