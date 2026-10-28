package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Runs the direct-link changeset (#1475) on agency_invite_link as it stands after 0052: existing
 * admin links keep working (no name, not temporary) and the rollback removes the new columns.
 */
class DirectLinksMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261028_direct_links_1475/changeSet.xml";

  @Test
  void update_Should_AddDirectLinkColumns_And_KeepExistingLinks() throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "INSERT INTO agency_invite_link (token, tenant_id, link_kind, chat_type, anonymity,"
                + " created_by_user_id, create_date, status)"
                + " VALUES ('old', 1, 'TENANT', 'LIVE_CHAT', 'FULL', 'admin', NOW(), 'ACTIVE')");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "token = 'old' AND name IS NULL AND temporary_account = FALSE"))
            .isEqualTo(1);
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO agency_invite_link (token, tenant_id, link_kind, chat_type, anonymity,"
                  + " consultant_id, created_by_user_id, create_date, status, name,"
                  + " temporary_account, update_date)"
                  + " VALUES ('new', 1, 'COUNSELLOR', 'VIDEO', 'FULL', 'c1', 'c1', NOW(),"
                  + " 'DISABLED', 'Flyer Schule', TRUE, NOW())");
        }
        assertThat(count(c, "consultant_id = 'c1' AND link_kind = 'COUNSELLOR'")).isEqualTo(1);

        liquibase.rollback(1, (String) null);
        assertThat(columnCount(c, "NAME")).isZero();
        assertThat(columnCount(c, "TEMPORARY_ACCOUNT")).isZero();
        assertThat(columnCount(c, "UPDATE_DATE")).isZero();
      }
    }
  }

  private static Connection newDatabase() throws Exception {
    return DriverManager.getConnection(
        "jdbc:h2:mem:direct-links-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "");
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  /** agency_invite_link as created by the earlier invite-link changesets plus 0052. */
  private static void createTableAsBefore(Connection c) throws Exception {
    try (var sql = c.createStatement()) {
      sql.execute(
          "CREATE TABLE agency_invite_link ("
              + " id BIGINT NOT NULL AUTO_INCREMENT,"
              + " token VARCHAR(64) NOT NULL UNIQUE,"
              + " tenant_id BIGINT NOT NULL,"
              + " topic_id BIGINT NULL,"
              + " link_kind VARCHAR(32) NOT NULL,"
              + " chat_type VARCHAR(32) NOT NULL,"
              + " anonymity VARCHAR(16) NOT NULL,"
              + " notes VARCHAR(500) NULL,"
              + " consultant_id VARCHAR(36) NULL,"
              + " agency_id BIGINT NULL,"
              + " consulting_type_id INT NULL,"
              + " created_by_user_id VARCHAR(36) NOT NULL,"
              + " created_by_username VARCHAR(255) NULL,"
              + " create_date DATETIME NOT NULL,"
              + " expires_at DATETIME NULL,"
              + " used_at DATETIME NULL,"
              + " used_by_session_id BIGINT NULL,"
              + " status VARCHAR(20) NOT NULL,"
              + " PRIMARY KEY (id))");
    }
  }

  private static int count(Connection c, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM agency_invite_link WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static int columnCount(Connection c, String column) throws Exception {
    try (var sql = c.createStatement();
        var rs =
            sql.executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                    + " WHERE TABLE_NAME = 'AGENCY_INVITE_LINK' AND COLUMN_NAME = '"
                    + column
                    + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
