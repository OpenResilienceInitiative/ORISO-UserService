package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class GroupSessionTenantMigrationTest {
  @Test
  void repairsOnlyUnambiguousGroupOwnershipAndIsIdempotent() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:group-backfill;MODE=MariaDB");
        var sql = connection.createStatement()) {
      sql.execute(
          "CREATE TABLE consultant (consultant_id VARCHAR(64) PRIMARY KEY, tenant_id BIGINT)");
      sql.execute(
          "CREATE TABLE session (id BIGINT PRIMARY KEY, consultant_id VARCHAR(64), user_id VARCHAR(64), tenant_id BIGINT, conversation_type VARCHAR(64))");
      sql.execute("INSERT INTO consultant VALUES ('owner',2),('technical',0),('unknown',NULL)");
      sql.execute(
          "INSERT INTO session VALUES (1,'owner','group-chat-system-2',NULL,'SELF_HELP'),(2,'owner','group-chat-system',NULL,'INTERNAL_GROUP'),(3,'owner','group-chat-system-2',7,'SELF_HELP'),(4,'owner','person',NULL,'AGENCY_COUNSELLING'),(5,'technical','group-chat-system',NULL,'SELF_HELP'),(6,'unknown','group-chat-system',NULL,'SELF_HELP'),(7,NULL,'group-chat-system',NULL,'SELF_HELP'),(8,'owner','group-chat-system-7',NULL,'SELF_HELP'),(9,'owner','person',NULL,'SELF_HELP')");
      var migration =
          Files.readString(
              Path.of(
                  "src/main/resources/db/changelog/changeset/0091_group_session_tenant/migrate.sql"));
      for (int run = 0; run < 2; run++) {
        sql.execute(migration);
        try (var rows = sql.executeQuery("SELECT tenant_id FROM session ORDER BY id")) {
          Long[] expected = {2L, 2L, 7L, null, null, null, null, null, null};
          for (var tenant : expected) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject(1, Long.class)).isEqualTo(tenant);
          }
          assertThat(rows.next()).isFalse();
        }
      }
    }
  }
}
