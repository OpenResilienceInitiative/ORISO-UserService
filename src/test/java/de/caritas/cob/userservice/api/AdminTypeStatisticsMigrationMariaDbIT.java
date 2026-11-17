package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

/**
 * ORISO-UserService#1666 on MariaDB: admin.type becomes VARCHAR(16) NOT NULL, and a column that was
 * already widened further by hand keeps its width and still becomes NOT NULL.
 */
class AdminTypeStatisticsMigrationMariaDbIT {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261117_admin_type_statistics_1666/changeSet.xml";
  private static final String WIDTH = "20261117-admin-type-width-1666";
  private static final String NOT_NULL = "20261117-admin-type-not-null-1666";

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @Test
  void theOldColumnIsWidenedAndStaysNotNull() throws Exception {
    try (Connection c = connect()) {
      reset(c, "type VARCHAR(6) NOT NULL");
      exec(c, "INSERT INTO admin (admin_id, type) VALUES ('a1', 'AGENCY')");

      update(c);

      assertThat(column(c)).isEqualTo("16|NO");
      assertThat(execType(c, WIDTH)).isEqualTo("EXECUTED");
      exec(c, "INSERT INTO admin (admin_id, type) VALUES ('a2', 'STATISTICS')");
      assertThat(typeOf(c, "a1")).isEqualTo("AGENCY");
    }
  }

  @Test
  void aNullableOldColumnIsWidenedAndMadeNotNull() throws Exception {
    try (Connection c = connect()) {
      reset(c, "type VARCHAR(6)");

      update(c);

      assertThat(column(c)).isEqualTo("16|NO");
      assertThat(execType(c, NOT_NULL)).isEqualTo("EXECUTED");
    }
  }

  @Test
  void aColumnWidenedFurtherByHandKeepsItsWidthAndBecomesNotNull() throws Exception {
    try (Connection c = connect()) {
      reset(c, "type VARCHAR(32)");
      exec(c, "INSERT INTO admin (admin_id, type) VALUES ('a1', 'A_TYPE_LONGER_THAN_SIXTEEN')");

      update(c);

      assertThat(execType(c, WIDTH)).isEqualTo("MARK_RAN");
      assertThat(execType(c, NOT_NULL)).isEqualTo("EXECUTED");
      assertThat(column(c)).isEqualTo("32|NO");
      assertThat(typeOf(c, "a1")).isEqualTo("A_TYPE_LONGER_THAN_SIXTEEN");
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static void reset(Connection c, String typeColumn) throws SQLException {
    exec(c, "DROP TABLE IF EXISTS admin");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOG");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
    exec(c, "CREATE TABLE admin (admin_id VARCHAR(36) NOT NULL PRIMARY KEY, " + typeColumn + ")");
  }

  private static void update(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    var liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
    liquibase.update(new Contexts(), new LabelExpression());
    if (!c.getAutoCommit()) c.commit();
  }

  private static String column(Connection c) throws SQLException {
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT character_maximum_length, is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = DATABASE() AND table_name = 'admin'"
                    + " AND column_name = 'type'")) {
      rs.next();
      return rs.getString(1) + "|" + rs.getString(2);
    }
  }

  private static String typeOf(Connection c, String id) throws SQLException {
    try (var sql = c.prepareStatement("SELECT type FROM admin WHERE admin_id = ?")) {
      sql.setString(1, id);
      try (var rs = sql.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static String execType(Connection c, String id) throws SQLException {
    try (var sql = c.prepareStatement("SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = ?")) {
      sql.setString(1, id);
      try (var rs = sql.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static void exec(Connection c, String statement) throws SQLException {
    try (var sql = c.createStatement()) {
      sql.execute(statement);
    }
    if (!c.getAutoCommit()) c.commit();
  }
}
