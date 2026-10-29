package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * The deletion log table: created by the changeset, no personal data columns, rollback drops it.
 */
class DeletionAuditLogMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261029_deletion_audit_log/changeSet.xml";

  @Test
  void masterChangelog_Should_IncludeTheDeletionAuditLogChangeset() throws Exception {
    var master =
        new ClassPathResource("db/changelog/userservice-master.xml")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG);
  }

  @Test
  void update_Should_CreateTheTable_And_RollbackDropsIt() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:del-audit-mig-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var st = c.createStatement()) {
          st.execute(
              "INSERT INTO deletion_audit_log (actor_id, actor_role, target_type, target_id,"
                  + " tenant_id, outcome, create_date)"
                  + " VALUES ('a', 'PLATFORM_ADMIN', 'TENANT', '5', NULL, 'EXECUTED', NOW())");
        }
        assertThat(columns(c))
            .containsExactlyInAnyOrder(
                "ID",
                "ACTOR_ID",
                "ACTOR_ROLE",
                "TARGET_TYPE",
                "TARGET_ID",
                "TENANT_ID",
                "OUTCOME",
                "CREATE_DATE");

        liquibase.rollback(1, (String) null);
        assertThat(columns(c)).isEmpty();
      }
    }
  }

  private static java.util.List<String> columns(Connection c) throws Exception {
    var names = new java.util.ArrayList<String>();
    try (var rs = c.getMetaData().getColumns(null, null, "DELETION_AUDIT_LOG", null)) {
      while (rs.next()) {
        names.add(rs.getString("COLUMN_NAME"));
      }
    }
    return names;
  }
}
