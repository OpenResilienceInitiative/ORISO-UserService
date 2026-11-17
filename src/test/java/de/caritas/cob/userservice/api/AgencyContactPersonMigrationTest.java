package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * Runs the ORISO-Admin#1534 changeset: an admin can carry an optional contact e-mail, and a
 * counselling centre and a Träger can each name one contact person and one deputy.
 */
class AgencyContactPersonMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261116_agency_contact_person_1534/changeSet.xml";

  @Test
  void update_Should_AddTheContactColumnAndTables_And_RollbackRemovesThem() throws Exception {
    try (Connection c = newDatabase()) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO admin (admin_id, email, contact_email) VALUES ('a-1', 'login@x.org',"
                  + " 'kontakt@x.org')");
          sql.execute(
              "INSERT INTO agency_contact_person (agency_id, tenant_id, contact_admin_id,"
                  + " deputy_admin_id, create_date, update_date) VALUES (7, 1, 'a-1', NULL,"
                  + " NOW(), NOW())");
          sql.execute(
              "INSERT INTO tenant_contact_person (tenant_id, contact_admin_id, deputy_admin_id,"
                  + " general_contact_email, create_date, update_date) VALUES (1, 'a-1', NULL,"
                  + " 'info@traeger.org', NOW(), NOW())");
        }
        assertThat(count(c, "admin", "contact_email = 'kontakt@x.org'")).isEqualTo(1);
        assertThat(count(c, "agency_contact_person", "agency_id = 7")).isEqualTo(1);
        assertThat(count(c, "tenant_contact_person", "tenant_id = 1")).isEqualTo(1);
        // One row per centre and per Träger.
        assertThatThrownBy(
                () -> {
                  try (var sql = c.createStatement()) {
                    sql.execute(
                        "INSERT INTO agency_contact_person (agency_id, create_date) VALUES (7,"
                            + " NOW())");
                  }
                })
            .isInstanceOf(SQLException.class);
        if (!c.getAutoCommit()) {
          c.commit();
        }

        liquibase.rollback(3, (String) null);
        assertThat(tableExists(c, "AGENCY_CONTACT_PERSON")).isFalse();
        assertThat(tableExists(c, "TENANT_CONTACT_PERSON")).isFalse();
        assertThat(columnExists(c, "ADMIN", "CONTACT_EMAIL")).isFalse();
        assertThat(count(c, "admin", "1 = 1")).as("admins survive the rollback").isEqualTo(1);
      }
    }
  }

  @Test
  void update_Should_MarkRan_When_TheObjectsAlreadyExist() throws Exception {
    try (Connection c = newDatabase()) {
      try (var sql = c.createStatement()) {
        sql.execute("ALTER TABLE admin ADD contact_email VARCHAR(255)");
        sql.execute("CREATE TABLE agency_contact_person (agency_id BIGINT PRIMARY KEY)");
        sql.execute("CREATE TABLE tenant_contact_person (tenant_id BIGINT PRIMARY KEY)");
      }
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      try (var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());

        try (var sql = c.createStatement();
            var rs =
                sql.executeQuery(
                    "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE EXECTYPE = 'MARK_RAN'")) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(3);
        }
      }
    }
  }

  private static Connection newDatabase() throws SQLException {
    var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:contact-person-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE admin (admin_id VARCHAR(36) NOT NULL, email VARCHAR(255) NOT NULL,"
              + " PRIMARY KEY (admin_id))");
    }
    return c;
  }

  private static boolean tableExists(Connection c, String table) throws SQLException {
    try (var rs = c.getMetaData().getTables(null, null, table, null)) {
      return rs.next();
    }
  }

  private static boolean columnExists(Connection c, String table, String column)
      throws SQLException {
    try (var rs = c.getMetaData().getColumns(null, null, table, column)) {
      return rs.next();
    }
  }

  private static int count(Connection c, String table, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
