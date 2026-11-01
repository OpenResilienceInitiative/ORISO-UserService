package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class MatrixCallGuestMigrationTest {
  private static final String CHANGELOG =
      "db/changelog/changeset/20261028_matrix_call_guest_1477/changeSet.xml";

  @Test
  void guestRowsAreUniquePerCallFollowTheirCallAndRollBackCleanly() throws Exception {
    try (var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:call-guest-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE matrix_call_binding(id BIGINT PRIMARY KEY)");
        sql.execute("INSERT INTO matrix_call_binding(id) VALUES(1),(2)");
      }
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
        l.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO matrix_call_guest(binding_id,matrix_user_id,admitted_by_matrix_id,admitted_at)"
                  + " VALUES(1,'@guest:example','@counsellor:example',1000)");
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO matrix_call_guest(binding_id,matrix_user_id,admitted_by_matrix_id,admitted_at)"
                              + " VALUES(1,'@guest:example','@counsellor:example',2000)"))
              .isInstanceOf(java.sql.SQLException.class);
          sql.execute(
              "INSERT INTO matrix_call_guest(binding_id,matrix_user_id,admitted_by_matrix_id,admitted_at,revoked_at)"
                  + " VALUES(2,'@guest:example','@counsellor:example',1000,3000)");
          try (var rows =
              sql.executeQuery(
                  "SELECT revoke_attempts, last_revoke_attempt_at FROM matrix_call_guest WHERE binding_id=2")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
            assertThat(rows.getObject(2)).isNull();
          }
          sql.execute("DELETE FROM matrix_call_binding WHERE id=1");
          try (var rows = sql.executeQuery("SELECT binding_id FROM matrix_call_guest")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(2L);
            assertThat(rows.next()).isFalse();
          }
          c.commit();
        }
        l.rollback(1, new Contexts(), new LabelExpression());
        try (var rows =
            c.getMetaData().getTables(null, null, "MATRIX_CALL_GUEST", new String[] {"TABLE"})) {
          assertThat(rows.next()).isFalse();
        }
        try (var sql = c.createStatement();
            var rows = sql.executeQuery("SELECT COUNT(*) FROM matrix_call_binding")) {
          rows.next();
          assertThat(rows.getInt(1)).isOne();
        }
      }
    }
  }
}
