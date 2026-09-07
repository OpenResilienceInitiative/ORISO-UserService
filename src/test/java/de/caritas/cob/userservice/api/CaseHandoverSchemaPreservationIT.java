package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real MariaDB convergence without rewriting policy, historical access, or cached policy. */
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class CaseHandoverSchemaPreservationIT {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshAndExistingSchemaConvergeWithoutChangingStoredFacts(boolean hasCoAccess)
      throws Exception {
    String schema = "handover_preservation_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect()) {
      execute(connection, "CREATE DATABASE " + schema);
      connection.setCatalog(schema);
      try {
        fixture(connection, hasCoAccess);
        String policyColumns = columns(connection, "case_handover_reason_policy");
        String requestColumns = columns(connection, "case_handover_request");
        var policyBefore =
            rows(
                connection,
                "SELECT " + policyColumns + " FROM case_handover_reason_policy ORDER BY code");
        var requestsBefore =
            rows(
                connection, "SELECT " + requestColumns + " FROM case_handover_request ORDER BY id");
        var cacheBefore =
            hasCoAccess
                ? rows(connection, "SELECT * FROM tenant_case_handover_policy_cache")
                : List.of();

        var database =
            DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        var migration =
            new Liquibase(
                "db/handover-compatibility-changelog.xml",
                new ClassLoaderResourceAccessor(),
                database);
        migration.update(new Contexts(), new LabelExpression());

        assertThat(
                rows(
                    connection,
                    "SELECT " + policyColumns + " FROM case_handover_reason_policy ORDER BY code"))
            .isEqualTo(policyBefore);
        assertThat(
                rows(
                    connection,
                    "SELECT " + requestColumns + " FROM case_handover_request ORDER BY id"))
            .isEqualTo(requestsBefore);
        assertThat(rows(connection, "SELECT * FROM tenant_case_handover_policy_cache"))
            .isEqualTo(cacheBefore);
        assertThat(columns(connection, "case_handover_request"))
            .contains(
                "access_type",
                "max_access_duration_minutes",
                "expires_at",
                "direction",
                "target_consultant_id",
                "offer_expires_at");
        assertThat(rows(connection, "SELECT DISTINCT direction FROM case_handover_request"))
            .isEqualTo(List.of(Map.of("direction", "PULL")));
        assertThat(rows(connection, "SELECT id FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED"))
            .isEqualTo(
                List.of(
                    Map.of("id", "0092-case-handover-neutral-reasons"),
                    Map.of("id", "0093-case-handover-push")));

        var allAfter = rows(connection, "SELECT * FROM case_handover_request ORDER BY id");
        // Exercise SQL idempotence independently of Liquibase's already-applied fast path.
        repeatSql(
            connection, "/db/changelog/changeset/0092_case_handover_neutral_reasons/migrate.sql");
        repeatSql(connection, "/db/changelog/changeset/0093_case_handover_push/migrate.sql");
        assertThat(rows(connection, "SELECT * FROM case_handover_request ORDER BY id"))
            .isEqualTo(allAfter);
        assertThat(
                rows(
                    connection,
                    "SELECT " + policyColumns + " FROM case_handover_reason_policy ORDER BY code"))
            .isEqualTo(policyBefore);
        assertThat(rows(connection, "SELECT * FROM tenant_case_handover_policy_cache"))
            .isEqualTo(cacheBefore);
      } finally {
        // This test owns only its uniquely created synthetic schema, never the supplied database.
        connection.setCatalog("information_schema");
        execute(connection, "DROP DATABASE " + schema);
      }
    }
  }

  private void fixture(Connection c, boolean existing) throws SQLException {
    execute(
        c,
        "CREATE TABLE case_handover_reason_policy (code VARCHAR(100) PRIMARY KEY, label VARCHAR(255) NOT NULL, client_consent_required BOOLEAN NOT NULL, access_allowed BOOLEAN NOT NULL, enabled BOOLEAN NOT NULL, display_order INT NOT NULL, policy_authority VARCHAR(255) NOT NULL, client_notification_templates JSON NULL, updated_at DATETIME NOT NULL)");
    execute(
        c,
        "CREATE TABLE case_handover_request (id BIGINT PRIMARY KEY, status VARCHAR(40), reason_code VARCHAR(100), reason_label VARCHAR(255), explanation TEXT, client_consent_required BOOLEAN, policy_authority VARCHAR(255), created_at DATETIME, resolved_at DATETIME)");
    execute(
        c,
        "INSERT INTO case_handover_reason_policy VALUES ('COUNSELLOR_ASKED_FOR_ADVICE','Stored advice label',1,1,1,10,'tenant-edited',NULL,'2026-01-01'),('COUNSELLOR_ON_HOLIDAY','Stored holiday label',1,1,0,20,'tenant-edited','{\"de\":\"Stored custom notice\"}','2026-02-02'),('COUNSELLOR_IS_ILL','Stored legacy label',0,1,1,40,'seeded',NULL,'2026-03-03'),('OTHER_EMERGENCY','Other emergency',0,1,1,30,'seeded',NULL,'2026-04-04'),('ADVICE_REQUESTED','Existing neutral override',0,0,0,99,'independent-admin-edit','{\"en\":\"Separate rule\"}','2026-05-05')");
    execute(
        c,
        "INSERT INTO case_handover_request VALUES (1,'GRANTED','COUNSELLOR_ASKED_FOR_ADVICE','Historical advice label','Historical explanation',1,'frozen-authority','2026-01-01','2026-01-02'),(2,'GRANTED','COUNSELLOR_ON_HOLIDAY','Historical holiday label','Second explanation',1,'other-frozen-authority','2026-02-01','2026-02-02'),(3,'PENDING_CLIENT_CONSENT','COUNSELLOR_IS_ILL','Original historical label','Private historical explanation',1,'frozen-policy','2026-03-01',NULL)");
    if (existing) {
      execute(
          c,
          "ALTER TABLE case_handover_reason_policy ADD COLUMN max_access_duration_minutes INT NULL");
      execute(
          c,
          "UPDATE case_handover_reason_policy SET max_access_duration_minutes=75 WHERE code='COUNSELLOR_ASKED_FOR_ADVICE'");
      execute(
          c,
          "ALTER TABLE case_handover_request ADD COLUMN access_type VARCHAR(20) NULL, ADD COLUMN max_access_duration_minutes INT NULL, ADD COLUMN expires_at DATETIME NULL");
      execute(
          c,
          "UPDATE case_handover_request SET access_type='CO_ACCESS',max_access_duration_minutes=75,expires_at='2026-01-02 01:15:00' WHERE id=1");
      execute(c, "UPDATE case_handover_request SET access_type='TAKEOVER' WHERE id=2");
      execute(
          c,
          "CREATE TABLE tenant_case_handover_policy_cache (tenant_id BIGINT PRIMARY KEY, policies LONGTEXT NOT NULL, refreshed_at DATETIME NOT NULL, stale_since DATETIME NULL)");
      execute(
          c,
          "INSERT INTO tenant_case_handover_policy_cache VALUES (40,'{\"duration\":180,\"holidayConsent\":false}','2026-06-01',NULL)");
    }
  }

  private void repeatSql(Connection c, String resource) throws Exception {
    try (var input = getClass().getResourceAsStream(resource)) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      sql = sql.replaceAll("(?m)^\\s*--.*$", "");
      for (String statement : sql.split(";")) {
        if (!statement.isBlank()) execute(c, statement);
      }
    }
  }

  private String columns(Connection c, String table) throws SQLException {
    try (var statement = c.createStatement();
        var result = statement.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
      var names = new ArrayList<String>();
      for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
        names.add(result.getMetaData().getColumnName(i));
      return String.join(",", names);
    }
  }

  private List<Map<String, String>> rows(Connection c, String sql) throws SQLException {
    try (var statement = c.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      var rows = new ArrayList<Map<String, String>>();
      while (result.next()) {
        var row = new LinkedHashMap<String, String>();
        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
          row.put(
              result.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT),
              result.getString(i));
        rows.add(row);
      }
      return rows;
    }
  }

  private void execute(Connection c, String sql) throws SQLException {
    try (var statement = c.createStatement()) {
      statement.execute(sql);
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(
        System.getenv("LIQUIBASE_IT_DB_URL"),
        System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"),
        System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
  }
}
