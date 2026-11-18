package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

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
 * ORISO-UserService#1666: admin.type widens to VARCHAR(16) and stays NOT NULL; each step is skipped
 * (MARK_RAN) where the database already has that state.
 */
class AdminTypeStatisticsMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261117_admin_type_statistics_1666/changeSet.xml";

  @Test
  void update_Should_WidenTheColumn_And_KeepItRequired() throws Exception {
    try (Connection c = newDatabase("type VARCHAR(6)")) {
      update(c);

      assertThat(columnSize(c)).isEqualTo(16);
      assertThat(nullable(c)).isFalse();
      assertThat(markedRan(c)).isZero();
    }
  }

  @Test
  void update_Should_MarkRan_When_TheColumnIsAlreadyWideAndRequired() throws Exception {
    try (Connection c = newDatabase("type VARCHAR(16) NOT NULL")) {
      update(c);

      assertThat(columnSize(c)).isEqualTo(16);
      assertThat(nullable(c)).isFalse();
      assertThat(markedRan(c)).isEqualTo(2);
    }
  }

  @Test
  void update_Should_OnlyAddNotNull_When_TheColumnIsAlreadyWide() throws Exception {
    try (Connection c = newDatabase("type VARCHAR(16)")) {
      update(c);

      assertThat(nullable(c)).isFalse();
      assertThat(markedRan(c)).isEqualTo(1);
    }
  }

  /** Not closed here: closing Liquibase closes the connection the assertions still read. */
  @SuppressWarnings("resource")
  private static void update(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
        .update(new Contexts(), new LabelExpression());
  }

  private static Connection newDatabase(String typeColumn) throws SQLException {
    var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:admin-type-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE admin (admin_id VARCHAR(36) NOT NULL, "
              + typeColumn
              + ", PRIMARY KEY (admin_id))");
    }
    return c;
  }

  private static int columnSize(Connection c) throws SQLException {
    try (var rs = c.getMetaData().getColumns(null, null, "ADMIN", "TYPE")) {
      rs.next();
      return rs.getInt("COLUMN_SIZE");
    }
  }

  private static boolean nullable(Connection c) throws SQLException {
    try (var rs = c.getMetaData().getColumns(null, null, "ADMIN", "TYPE")) {
      rs.next();
      return "YES".equals(rs.getString("IS_NULLABLE"));
    }
  }

  private static int markedRan(Connection c) throws SQLException {
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE EXECTYPE = 'MARK_RAN'")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
