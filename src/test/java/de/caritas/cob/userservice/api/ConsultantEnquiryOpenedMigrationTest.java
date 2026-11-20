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

class ConsultantEnquiryOpenedMigrationTest {
  private static final String CHANGELOG =
      "db/changelog/changeset/20261102_consultant_enquiry_opened_1549/changeSet.xml";

  @Test
  void marksAreUniquePerCounsellorAndEnquiryGoWithSessionOrCounsellorAndRollBackCleanly()
      throws Exception {
    try (var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:enquiry-opened-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = c.createStatement()) {
        sql.execute("CREATE TABLE consultant(consultant_id VARCHAR(36) PRIMARY KEY)");
        sql.execute("CREATE TABLE session(id BIGINT UNSIGNED PRIMARY KEY)");
        sql.execute("INSERT INTO consultant VALUES('A'),('B')");
        sql.execute("INSERT INTO session VALUES(1),(2)");
      }
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var l = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
        l.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO consultant_enquiry_opened(consultant_id,session_id,opened_at)"
                  + " VALUES('A',1,NOW()),('B',1,NOW()),('A',2,NOW())");
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO consultant_enquiry_opened(consultant_id,session_id,opened_at)"
                              + " VALUES('A',1,NOW())"))
              .isInstanceOf(java.sql.SQLException.class);
          assertThatThrownBy(
                  () ->
                      sql.execute(
                          "INSERT INTO consultant_enquiry_opened(consultant_id,session_id,opened_at)"
                              + " VALUES('C',1,NOW())"))
              .isInstanceOf(java.sql.SQLException.class);

          sql.execute("DELETE FROM session WHERE id=2");
          assertThat(count(sql)).isEqualTo(2);
          sql.execute("DELETE FROM consultant WHERE consultant_id='B'");
          assertThat(count(sql)).isEqualTo(1);
          c.commit();
        }
        l.rollback(1, null);
        try (var sql = c.createStatement();
            var rows =
                sql.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE LOWER(table_name)='consultant_enquiry_opened'")) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
      }
    }
  }

  private static int count(java.sql.Statement sql) throws Exception {
    try (var rows = sql.executeQuery("SELECT COUNT(*) FROM consultant_enquiry_opened")) {
      rows.next();
      return rows.getInt(1);
    }
  }
}
