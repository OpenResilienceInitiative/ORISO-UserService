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
 * Runs the direct-link changesets (#1475) on agency_invite_link (after 0052) and
 * group_chat_join_request (after 1499): existing rows keep working, a call knock without series
 * fits, and the rollback restores both tables.
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

        liquibase.rollback(2, (String) null);
        assertThat(columnCount(c, "NAME")).isZero();
        assertThat(columnCount(c, "TEMPORARY_ACCOUNT")).isZero();
        assertThat(columnCount(c, "UPDATE_DATE")).isZero();
      }
    }
  }

  @Test
  void update_Should_LetCallKnocksLiveBesideSeriesKnocks_And_RollbackRemovesThem()
      throws Exception {
    try (Connection c = newDatabase()) {
      createTableAsBefore(c);
      try (var sql = c.createStatement()) {
        sql.execute(
            "INSERT INTO group_chat_join_request (series_id, consultant_id, status, via,"
                + " requested_at) VALUES (1, 'c1', 'PENDING', 'INVITE_LINK', NOW())");
      }
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO group_chat_join_request (session_id, user_id, invite_link_id, medium,"
                  + " status, via, requested_at)"
                  + " VALUES (500, 'u1', 9, 'VIDEO', 'PENDING', 'DIRECT_LINK', NOW())");
        }
        assertThat(knockCount(c, "via = 'DIRECT_LINK' AND series_id IS NULL")).isEqualTo(1);
        // A knock goes with its conversation.
        try (var sql = c.createStatement()) {
          sql.execute(
              "INSERT INTO group_chat_join_request (session_id, user_id, medium, status, via,"
                  + " requested_at) VALUES (501, 'u2', 'AUDIO', 'PENDING', 'DIRECT_LINK', NOW())");
          sql.execute("DELETE FROM session WHERE id = 501");
        }
        assertThat(knockCount(c, "via = 'DIRECT_LINK'")).isEqualTo(1);

        liquibase.rollback(1, (String) null);
        assertThat(knockCount(c, "1 = 1")).as("series knock kept, call knock gone").isEqualTo(1);
        assertThat(knockCount(c, "via = 'INVITE_LINK'")).isEqualTo(1);
        // Both columns are mandatory again.
        assertThat(insertFails(c, "NULL, 'c1'")).as("series_id NOT NULL").isTrue();
        assertThat(insertFails(c, "1, NULL")).as("consultant_id NOT NULL").isTrue();
        // The foreign key survived the change and its rollback.
        try (var sql = c.createStatement()) {
          sql.execute("DELETE FROM chat WHERE id = 1");
        }
        assertThat(knockCount(c, "1 = 1")).as("cascade still in place").isZero();
      }
    }
  }

  private static boolean insertFails(Connection c, String seriesAndConsultant) {
    try (var sql = c.createStatement()) {
      sql.execute(
          "INSERT INTO group_chat_join_request (series_id, consultant_id, status, via,"
              + " requested_at) VALUES ("
              + seriesAndConsultant
              + ", 'PENDING', 'INVITE_LINK', NOW())");
      return false;
    } catch (java.sql.SQLException expected) {
      return true;
    }
  }

  private static int knockCount(Connection c, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM group_chat_join_request WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
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
      // 20260923_group_chat_join_request_1499 + 20260926_group_chat_admission_1499, with the
      // production foreign key to chat, so the nullability change runs against it.
      sql.execute("CREATE TABLE chat (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
      sql.execute("INSERT INTO chat (id) VALUES (1)");
      sql.execute("CREATE TABLE session (id BIGINT UNSIGNED NOT NULL, PRIMARY KEY (id))");
      sql.execute("INSERT INTO session (id) VALUES (500), (501)");
      sql.execute(
          "CREATE TABLE group_chat_join_request ("
              + " id BIGINT NOT NULL AUTO_INCREMENT,"
              + " series_id BIGINT UNSIGNED NOT NULL,"
              + " consultant_id VARCHAR(36) NOT NULL,"
              + " status VARCHAR(16) NOT NULL,"
              + " via VARCHAR(32) NOT NULL DEFAULT 'INVITE_LINK',"
              + " admitted_role VARCHAR(16) NULL,"
              + " requested_at DATETIME NOT NULL,"
              + " decided_at DATETIME NULL,"
              + " decided_by VARCHAR(36) NULL,"
              + " admission_requested_at DATETIME NULL,"
              + " admission_attempt_count INT NOT NULL DEFAULT 0,"
              + " admission_last_attempt_at DATETIME NULL,"
              + " PRIMARY KEY (id),"
              + " CONSTRAINT fk_gcjr_series FOREIGN KEY (series_id) REFERENCES chat (id)"
              + " ON DELETE CASCADE)");
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
