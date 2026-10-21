package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.MariaDBDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual InnoDB transactions and migration, using only the disposable hosted proof database. */
@EnabledIfEnvironmentVariable(named = "CALLER_FAIRNESS_DB_URL", matches = ".+")
class AccountLifecycleRetryFairnessMariaDbIT extends AccountLifecycleRetryFairnessSqlTest {
  @Override
  DataSource newDatabase() {
    String url = System.getenv("CALLER_FAIRNESS_DB_URL");
    assertEquals("jdbc:mariadb://127.0.0.1:3306/caller_fairness", url);
    var source =
        new DriverManagerDataSource(url, "root", System.getenv("CALLER_FAIRNESS_DB_PASSWORD"));
    var jdbc = new JdbcTemplate(source);
    for (String table :
        List.of(
            "identity_lifecycle_effect",
            "identity_lifecycle_outbox",
            "identity_lifecycle_attempt",
            "DATABASECHANGELOG",
            "DATABASECHANGELOGLOCK")) jdbc.execute("DROP TABLE IF EXISTS " + table);
    return source;
  }

  @Test
  void thePinnedServerUsesInnoDbAndTheExactDueQueueIndex() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("CALLER_FAIRNESS_DB_URL"),
                "root",
                System.getenv("CALLER_FAIRNESS_DB_PASSWORD")));
    String version = jdbc.queryForObject("SELECT VERSION()", String.class);
    assertNotNull(version);
    assertTrue(version.startsWith(System.getenv("CALLER_FAIRNESS_DB_VERSION") + "-"), version);
    System.out.println("Caller fairness native server: " + version);
    assertEquals(
        List.of("InnoDB", "InnoDB", "InnoDB"),
        jdbc.queryForList(
            "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('identity_lifecycle_attempt','identity_lifecycle_outbox','identity_lifecycle_effect') ORDER BY TABLE_NAME",
            String.class));
    assertEquals(
        List.of("status", "next_attempt_at", "created_at", "id"),
        jdbc.queryForList(
            "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND INDEX_NAME='idx_identity_lifecycle_outbox_due' ORDER BY SEQ_IN_INDEX",
            String.class));
  }

  @Test
  void theActualLiquibaseChangeSetCanRollBackAndUpgradeAnExistingPendingIntent() throws Exception {
    var source = newDatabase();
    var jdbc = new JdbcTemplate(source);
    var database = new MariaDBDatabase();
    database.setConnection(new JdbcConnection(source.getConnection()));
    try (var migration =
        new Liquibase(
            "db/changelog/changeset/20261021_identity_lifecycle_367/changeSet.xml",
            new ClassLoaderResourceAccessor(),
            database)) {
      migration.update(new Contexts(), new LabelExpression());
      jdbc.update(
          "INSERT INTO identity_lifecycle_attempt(id,request_key,identity_json,origin_kind,workflow_id,tenant_id,execution_id,original_execution_id,generation,status,journal_version,update_date) VALUES ('attempt','request','{}','REGISTRATION','workflow','7','execution','execution',0,'COMMIT_REQUESTED',1,?)",
          Timestamp.from(Instant.parse("2026-10-21T12:00:00Z")));
      jdbc.update(
          "INSERT INTO identity_lifecycle_outbox(id,attempt_id,operation,body_json,status,created_at) VALUES ('intent','attempt','COMMIT','immutable-body','PENDING',?)",
          Timestamp.from(Instant.parse("2026-10-21T12:00:00Z")));
      var original =
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox");
      migration.rollback(1, new Contexts(), new LabelExpression());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND COLUMN_NAME='next_attempt_at'",
              Integer.class));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='identity_lifecycle_outbox' AND INDEX_NAME='idx_identity_lifecycle_outbox_due'",
              Integer.class));
      assertEquals(
          original,
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox"));
      migration.update(new Contexts(), new LabelExpression());
      assertEquals(
          original,
          jdbc.queryForList(
              "SELECT id,attempt_id,operation,body_json,status,created_at FROM identity_lifecycle_outbox"));
      assertEquals(
          Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
          jdbc.queryForObject(
              "SELECT next_attempt_at FROM identity_lifecycle_outbox WHERE id='intent'",
              Timestamp.class));
      migration.rollback(2, new Contexts(), new LabelExpression());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('identity_lifecycle_attempt','identity_lifecycle_outbox','identity_lifecycle_effect')",
              Integer.class));
    }
  }
}
