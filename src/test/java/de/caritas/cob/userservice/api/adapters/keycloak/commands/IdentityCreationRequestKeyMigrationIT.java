package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityCreationRequestKeyMigrationIT {
  @Test
  void backfillReleasesOnlyCommittedReplayKeysAndPreservesReceiptsAndPendingAttempts()
      throws Exception {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:replay-keys-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MariaDB");
    migrate(source, "20261007_task_identity_creation");
    var jdbc = new JdbcTemplate(source);
    for (var state : java.util.List.of("COMMITTED", "COMPENSATED", "OPEN", "COMMIT_REQUESTED")) {
      jdbc.update(
          "INSERT INTO identity_creation_attempt (id,request_key,account_id,creation_proof,origin_kind,registration_kind,tenant_id,initial_roles,provenance,status,update_date) VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
          state,
          "key-" + state,
          "account-" + state,
          "proof-" + state,
          "INVITATION",
          "CONSULTANT",
          42L,
          "consultant",
          "invite-7",
          state);
    }
    var before =
        jdbc.queryForList(
            "SELECT id,account_id,creation_proof,origin_kind,registration_kind,tenant_id,initial_roles,provenance,status,update_date FROM identity_creation_attempt ORDER BY id");
    migrate(source, "20261008_creation_terminal_replay_keys");
    migrate(source, "20261008_creation_terminal_replay_keys");
    assertThat(
            jdbc.queryForObject(
                "SELECT request_key FROM identity_creation_attempt WHERE id='COMMITTED'",
                String.class))
        .isNull();
    for (var state : java.util.List.of("COMPENSATED", "OPEN", "COMMIT_REQUESTED"))
      assertThat(
              jdbc.queryForObject(
                  "SELECT request_key FROM identity_creation_attempt WHERE id=?",
                  String.class,
                  state))
          .isEqualTo("key-" + state);
    assertThat(
            jdbc.queryForList(
                "SELECT id,account_id,creation_proof,origin_kind,registration_kind,tenant_id,initial_roles,provenance,status,update_date FROM identity_creation_attempt ORDER BY id"))
        .isEqualTo(before);
  }

  private static void migrate(JdbcDataSource source, String directory) throws Exception {
    try (var connection = source.getConnection()) {
      new Liquibase(
              "db/changelog/changeset/" + directory + "/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              new JdbcConnection(connection))
          .update(new Contexts());
    }
  }
}
