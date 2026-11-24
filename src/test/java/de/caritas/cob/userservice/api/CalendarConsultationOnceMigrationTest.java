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
 * One-off Sprechstunden (ORISO-Frontend#2494): the table is created by its changeset, included
 * after the weekly Sprechstunden, over_blocker defaults to false, and rollback drops it.
 */
class CalendarConsultationOnceMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261124_calendar_consultation_once_2494/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheChangeset_AfterTheWeeklySprechstunden() throws Exception {
    var includes = activeIncludes("db/changelog/userservice-master.xml");

    var weekly =
        includes.indexOf(
            "db/changelog/changeset/20261117_calendar_consultation_hours_2287/changeSet.xml");
    assertThat(weekly).as("weekly Sprechstunden changeset included").isNotNegative();
    assertThat(includes.indexOf(CHANGELOG)).as("one-off changeset included").isGreaterThan(weekly);
  }

  @Test
  void update_Should_CreateTheTable_WithItsDefault_And_RollbackDropsIt() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:calendar-consultation-once-" + UUID.randomUUID() + ";MODE=MariaDB",
            "sa",
            "")) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var st = c.createStatement()) {
          st.execute(
              "INSERT INTO calendar_consultation_once (id, consultant_id, start_at, end_at,"
                  + " notice_minutes) VALUES ('"
                  + UUID.randomUUID()
                  + "', 'synthetic-consultant', '2026-11-24 09:00:00', '2026-11-24 10:00:00',"
                  + " 30)");
          try (var rs = st.executeQuery("SELECT over_blocker FROM calendar_consultation_once")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("over_blocker")).isFalse();
          }
        }
        assertThat(columns(c, "CALENDAR_CONSULTATION_ONCE"))
            .containsExactlyInAnyOrder(
                "ID", "CONSULTANT_ID", "START_AT", "END_AT", "NOTICE_MINUTES", "OVER_BLOCKER");

        // The index and the table.
        liquibase.rollback(2, (String) null);
        assertThat(columns(c, "CALENDAR_CONSULTATION_ONCE")).isEmpty();
      }
    }
  }

  @Test
  void update_Should_AddTheIndex_ToATableMadeWithoutIt_And_RollbackDropsOnlyTheIndex()
      throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:calendar-consultation-once-" + UUID.randomUUID() + ";MODE=MariaDB",
            "sa",
            "")) {
      try (var st = c.createStatement()) {
        st.execute(
            "CREATE TABLE calendar_consultation_once (id CHAR(36) NOT NULL PRIMARY KEY,"
                + " consultant_id VARCHAR(36) NOT NULL, start_at DATETIME(6) NOT NULL,"
                + " end_at DATETIME(6) NOT NULL, notice_minutes INT NOT NULL,"
                + " over_blocker BOOLEAN DEFAULT FALSE NOT NULL)");
      }
      assertThat(indexColumns(c)).isEmpty();
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
        assertThat(indexColumns(c)).containsExactly("CONSULTANT_ID", "END_AT");

        // Applying it again changes nothing and does not fail.
        liquibase.update(new Contexts(), new LabelExpression());
        assertThat(indexColumns(c)).containsExactly("CONSULTANT_ID", "END_AT");

        // Rolling back only the index changeset keeps the table.
        liquibase.rollback(1, (String) null);
        assertThat(indexColumns(c)).isEmpty();
        assertThat(columns(c, "CALENDAR_CONSULTATION_ONCE")).hasSize(6);
      }
    }
  }

  /** The columns of the one-off index, in index order; empty without the index. */
  private static List<String> indexColumns(Connection c) throws Exception {
    var names = new ArrayList<String>();
    try (var rs =
        c.getMetaData().getIndexInfo(null, null, "CALENDAR_CONSULTATION_ONCE", false, false)) {
      while (rs.next()) {
        if ("IDX_CALENDAR_CONSULTATION_ONCE_CONSULTANT_END"
            .equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
          names.add(rs.getShort("ORDINAL_POSITION") - 1, rs.getString("COLUMN_NAME").toUpperCase());
        }
      }
    }
    return names;
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
