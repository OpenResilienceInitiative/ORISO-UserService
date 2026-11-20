package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class InquiryAcceptanceNoticeMigrationTest {
  @Test
  void additiveNoticeSchemaDoesNotBackfillAndCascadeDeletesOnlyThatSessionsFact() throws Exception {
    try (var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:acceptance-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE session(id BIGINT UNSIGNED PRIMARY KEY)");
        sql.execute("INSERT INTO session(id) VALUES(1),(2)");
      }
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l =
          new Liquibase(
              "db/changelog/changeset/20261009_inquiry_acceptance_notice/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              db)) {
        l.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          try (var rows = sql.executeQuery("SELECT COUNT(*) FROM inquiry_acceptance_notice")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
          }
          sql.execute(
              "INSERT INTO inquiry_acceptance_notice(session_id,owner_id,ownership_revision,accepted_at_utc,title,description,delivery_state) VALUES(1,'public-owner',1,CURRENT_TIMESTAMP,'Request accepted','The request was accepted.','PREPARING')");
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO inquiry_acceptance_notice(session_id,owner_id,ownership_revision,accepted_at_utc,title,description,delivery_state) VALUES(1,'duplicate',2,CURRENT_TIMESTAMP,'Request accepted','The request was accepted.','PREPARING')"))
              .isInstanceOf(java.sql.SQLException.class);
          sql.execute("DELETE FROM session WHERE id=1");
          try (var rows = sql.executeQuery("SELECT COUNT(*) FROM inquiry_acceptance_notice")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
          }
          try (var rows = sql.executeQuery("SELECT COUNT(*) FROM session WHERE id=2")) {
            rows.next();
            assertThat(rows.getInt(1)).isOne();
          }
          c.commit();
        }
        l.rollback(1, new Contexts(), new LabelExpression());
        try (var sql = c.createStatement();
            var rows = sql.executeQuery("SELECT COUNT(*) FROM session WHERE id=2")) {
          rows.next();
          assertThat(rows.getInt(1)).isOne();
        }
      }
    }
  }
}
