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
 * The day exception and working hours setting tables (ADR-032, addendum v1.2): created by their
 * changesets, dropped again, and a table made by hand is kept and still gets its index.
 */
class CalendarWorkingDayExceptionMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261114_calendar_working_hours_exceptions_1000/changeSet.xml";
  private static final String INDEX = "IDX_CALENDAR_WORKING_DAY_EXCEPTION_CONSULTANT_DATE";

  @Test
  void masterChangelog_Should_IncludeTheChangeset_AfterTheWorkingHours() throws Exception {
    // Parsed, so an include inside an XML comment does not count.
    var includes = activeIncludes("db/changelog/userservice-master.xml");

    var workingHours =
        includes.indexOf(
            "db/changelog/changeset/20261114_calendar_working_hours_1000/changeSet.xml");
    var exceptions = includes.indexOf(CHANGELOG);

    assertThat(workingHours).as("working hours changeset included").isNotNegative();
    assertThat(exceptions).as("day exception changeset included").isGreaterThan(workingHours);
  }

  @Test
  void update_Should_CreateBothTablesAndTheIndex_And_RollbackDropsThem() throws Exception {
    try (Connection c = database()) {
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var st = c.createStatement()) {
          st.execute(
              "INSERT INTO calendar_working_day_exception (id, consultant_id, exception_date,"
                  + " start_time, end_time) VALUES ('"
                  + UUID.randomUUID()
                  + "', 'synthetic-consultant', '2026-11-16', '08:00:00', '12:00:00')");
          st.execute(
              "INSERT INTO calendar_working_day_exception (id, consultant_id, exception_date)"
                  + " VALUES ('"
                  + UUID.randomUUID()
                  + "', 'synthetic-consultant', '2026-11-17')");
          st.execute(
              "INSERT INTO calendar_working_hours_setting (consultant_id, holiday_region)"
                  + " VALUES ('synthetic-consultant', 'BY')");
          st.execute("INSERT INTO calendar_working_hours_setting (consultant_id) VALUES ('other')");
        }
        assertThat(columns(c, "CALENDAR_WORKING_DAY_EXCEPTION"))
            .containsExactlyInAnyOrder(
                "ID", "CONSULTANT_ID", "EXCEPTION_DATE", "START_TIME", "END_TIME");
        assertThat(columns(c, "CALENDAR_WORKING_HOURS_SETTING"))
            .containsExactlyInAnyOrder("CONSULTANT_ID", "HOLIDAY_REGION");
        assertThat(indexes(c)).contains(INDEX);

        liquibase.rollback(3, (String) null);
        assertThat(columns(c, "CALENDAR_WORKING_DAY_EXCEPTION")).isEmpty();
        assertThat(columns(c, "CALENDAR_WORKING_HOURS_SETTING")).isEmpty();
      }
    }
  }

  @Test
  void update_Should_KeepTablesMadeByHand_AndAddTheMissingIndex() throws Exception {
    try (Connection c = database()) {
      try (var st = c.createStatement()) {
        st.execute(
            "CREATE TABLE calendar_working_day_exception (id CHAR(36) PRIMARY KEY,"
                + " consultant_id VARCHAR(36) NOT NULL, exception_date DATE NOT NULL,"
                + " start_time TIME, end_time TIME)");
        st.execute(
            "INSERT INTO calendar_working_day_exception (id, consultant_id, exception_date)"
                + " VALUES ('"
                + UUID.randomUUID()
                + "', 'synthetic-consultant', '2026-11-17')");
        st.execute(
            "CREATE TABLE calendar_working_hours_setting (consultant_id VARCHAR(36) PRIMARY KEY,"
                + " holiday_region VARCHAR(2))");
      }
      // Closing Liquibase closes the connection: look before that.
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(indexes(c)).contains(INDEX);
        assertThat(count(c, "SELECT COUNT(*) FROM calendar_working_day_exception")).isEqualTo(1);
        assertThat(count(c, "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE EXECTYPE = 'MARK_RAN'"))
            .isEqualTo(2);
        assertThat(count(c, "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE EXECTYPE = 'EXECUTED'"))
            .isEqualTo(1);
      }
    }
  }

  private static int count(Connection c, String sql) throws Exception {
    try (var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
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

  private static Connection database() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:calendar-working-day-exception-" + UUID.randomUUID() + ";MODE=MariaDB",
        "sa",
        "");
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
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

  private static List<String> indexes(Connection c) throws Exception {
    var names = new ArrayList<String>();
    try (var rs =
        c.getMetaData().getIndexInfo(null, null, "CALENDAR_WORKING_DAY_EXCEPTION", false, false)) {
      while (rs.next()) {
        names.add(rs.getString("INDEX_NAME"));
      }
    }
    return names;
  }
}
