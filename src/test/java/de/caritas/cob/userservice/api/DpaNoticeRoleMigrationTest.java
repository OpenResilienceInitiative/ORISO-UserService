package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

class DpaNoticeRoleMigrationTest {

  @Test
  void legacyAdminNoticeIsPreservedWhileSignerCanClaimSameVersionOnce() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:dpa-roles-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      try (var sql = connection.createStatement()) {
        sql.execute(
            "CREATE TABLE dpa_signed_notice (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " tenant_id BIGINT NOT NULL, dpa_version VARCHAR(64) NOT NULL,"
                + " recipient_email VARCHAR(255) NOT NULL, signed_at DATETIME, sent_at DATETIME,"
                + " create_date DATETIME NOT NULL,"
                + " CONSTRAINT uq_dpa_signed_notice_tenant_version UNIQUE(tenant_id, dpa_version))");
        sql.execute(
            "INSERT INTO dpa_signed_notice (tenant_id,dpa_version,recipient_email,create_date)"
                + " VALUES (42,'v1','admin@example.org',CURRENT_TIMESTAMP)");
        connection.commit();
      }
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var liquibase =
          new Liquibase(
              "db/changelog/changeset/20261006_dpa_notice_role/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              database)) {
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = connection.createStatement()) {
          try (var rows =
              sql.executeQuery("SELECT notice_role FROM dpa_signed_notice WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("ADMIN");
          }
          sql.execute(
              "INSERT INTO dpa_signed_notice"
                  + " (tenant_id,dpa_version,notice_role,recipient_email,create_date)"
                  + " VALUES(42,'v1','SIGNER','admin@example.org',CURRENT_TIMESTAMP)");
          assertThrows(
              SQLException.class,
              () ->
                  sql.execute(
                      "INSERT INTO dpa_signed_notice"
                          + " (tenant_id,dpa_version,notice_role,recipient_email,create_date)"
                          + " VALUES(42,'v1','SIGNER','admin@example.org',CURRENT_TIMESTAMP)"));
          assertThrows(
              SQLException.class,
              () ->
                  sql.execute(
                      "INSERT INTO dpa_signed_notice"
                          + " (tenant_id,dpa_version,notice_role,recipient_email,create_date)"
                          + " VALUES(42,'v1','ADMIN','admin@example.org',CURRENT_TIMESTAMP)"));
        }
      }
    }
  }
}
