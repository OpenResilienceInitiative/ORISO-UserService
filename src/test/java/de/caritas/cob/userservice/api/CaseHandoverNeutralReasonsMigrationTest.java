package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/** #1536: runs the neutral-reasons changeset forward and back on a seeded legacy table. */
class CaseHandoverNeutralReasonsMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20260923_case_handover_neutral_reasons/changeSet.xml";

  @Test
  void updateRetiresOldRowsAndRollbackRestoresExactlyThePreviousState() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:neutral-reasons-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      seed(c);
      Map<String, String> before = rows(c);
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        Map<String, String> after = rows(c);
        assertThat(after)
            .containsEntry("COUNSELLOR_ASKED_FOR_ADVICE", "enabled=false")
            .containsEntry("COUNSELLOR_IS_ILL", "enabled=false")
            .containsEntry("COUNSELLOR_ON_HOLIDAY", "enabled=false")
            .containsEntry("COUNSELLOR_LEFT", "enabled=false")
            .containsEntry("ADVICE_REQUESTED", "enabled=true")
            .containsEntry("UNPLANNED_ABSENCE", "enabled=true")
            .containsEntry("ASSIGNMENT_ENDED", "enabled=true")
            // Pre-existing neutral row is left as it was.
            .containsEntry("PLANNED_ABSENCE", "enabled=true label=Custom planned");
        assertThat(templatesOf(c, "UNPLANNED_ABSENCE")).isNull();

        liquibase.rollback(1, new Contexts(), new LabelExpression());
        // Closing Liquibase closes the connection, so assert inside.
        assertThat(rows(c)).isEqualTo(before);
      }
    }
  }

  private void seed(Connection c) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE case_handover_reason_policy ("
              + " code VARCHAR(100) NOT NULL PRIMARY KEY,"
              + " label VARCHAR(255) NOT NULL,"
              + " client_consent_required TINYINT NOT NULL DEFAULT 0,"
              + " access_allowed TINYINT NOT NULL DEFAULT 1,"
              + " enabled TINYINT NOT NULL DEFAULT 1,"
              + " display_order INT NOT NULL DEFAULT 100,"
              + " policy_authority VARCHAR(255) NOT NULL,"
              + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + " client_notification_templates VARCHAR(4000) NULL,"
              + " max_access_duration_minutes INT NULL,"
              + " client_consent_mode VARCHAR(20) NOT NULL DEFAULT 'NONE',"
              + " retired_by_0096 BOOLEAN NOT NULL DEFAULT FALSE)");
      insert(sql, "COUNSELLOR_ASKED_FOR_ADVICE", "Counsellor asked for advice", 1, "{}");
      insert(sql, "COUNSELLOR_IS_ILL", "Counsellor is ill", 1, "{\"de\":\"ist leider erkrankt\"}");
      insert(sql, "COUNSELLOR_LEFT", "Counsellor does not work here anymore", 1, null);
      // Admin-disabled before the migration: must stay disabled after rollback.
      insert(sql, "COUNSELLOR_ON_HOLIDAY", "Counsellor is on holiday", 0, null);
      // Neutral row that already existed, e.g. from a manual seed.
      insert(sql, "PLANNED_ABSENCE", "Custom planned", 1, null);
    }
  }

  private void insert(
      java.sql.Statement sql, String code, String label, int enabled, String templates)
      throws Exception {
    sql.execute(
        String.format(
            "INSERT INTO case_handover_reason_policy (code, label, enabled, policy_authority,"
                + " client_notification_templates) VALUES ('%s', '%s', %d, 'test', %s)",
            code, label, enabled, templates == null ? "NULL" : "'" + templates + "'"));
  }

  private Map<String, String> rows(Connection c) throws Exception {
    Map<String, String> rows = new LinkedHashMap<>();
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT code, label, enabled FROM case_handover_reason_policy ORDER BY code")) {
      while (rs.next()) {
        String code = rs.getString("code");
        String value = "enabled=" + rs.getBoolean("enabled");
        if ("PLANNED_ABSENCE".equals(code)) {
          value += " label=" + rs.getString("label");
        }
        rows.put(code, value);
      }
    }
    return rows;
  }

  private String templatesOf(Connection c, String code) throws Exception {
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT client_notification_templates FROM case_handover_reason_policy"
                    + " WHERE code = '"
                    + code
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
