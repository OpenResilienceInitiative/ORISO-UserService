package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Runs the call share link changeset (#1487) after #1475 and #1477: existing links keep working
 * with "Anklopfen" on, one link per call, the link goes with its call, and the rollback restores
 * both tables.
 */
class CallShareLinkMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changeset/20261028_call_share_link_1487/changeSet.xml";

  @Test
  void update_Should_AddCallShareLinks_And_RollbackRemovesThem() throws Exception {
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:call-share-" + UUID.randomUUID() + ";MODE=MariaDB", "sa", "")) {
      createTablesAsBefore(c);
      exec(c, insertLink("old", "COUNSELLOR", "NULL"));
      try (var liquibase = liquibase(c)) {
        liquibase.update(new Contexts(), new LabelExpression());

        assertThat(count(c, "agency_invite_link", "token = 'old' AND knock = TRUE")).isEqualTo(1);
        exec(c, insertLink("call", "CALL", "1"));
        assertThatThrownBy(() -> exec(c, insertLink("call-2", "CALL", "1")))
            .as("one link per call")
            .isInstanceOf(java.sql.SQLException.class);
        exec(
            c,
            "INSERT INTO group_chat_join_request (invite_link_id, user_id, medium, status, via,"
                + " requested_at) VALUES (1, 'u1', 'VIDEO', 'PENDING', 'CALL_LINK', NOW())");
        exec(c, "DELETE FROM matrix_call_binding WHERE id = 1");
        assertThat(count(c, "agency_invite_link", "link_kind = 'CALL'"))
            .as("the link goes with its call")
            .isZero();

        exec(c, insertLink("call-3", "CALL", "2"));
        liquibase.rollback(1, (String) null);
        assertThat(count(c, "agency_invite_link", "1 = 1")).as("only the old link").isEqualTo(1);
        assertThat(count(c, "group_chat_join_request", "via = 'CALL_LINK'")).isZero();
        assertThat(columnCount(c, "KNOCK")).isZero();
        assertThat(columnCount(c, "CALL_BINDING_ID")).isZero();
      }
    }
  }

  private static String insertLink(String token, String kind, String binding) {
    var columns = binding.equals("NULL") ? "" : ", call_binding_id";
    var values = binding.equals("NULL") ? "" : ", " + binding;
    return "INSERT INTO agency_invite_link (token, tenant_id, link_kind, chat_type, anonymity,"
        + " created_by_user_id, create_date, status"
        + columns
        + ") VALUES ('"
        + token
        + "', 1, '"
        + kind
        + "', 'VIDEO', 'FULL', 'c1', NOW(), 'ACTIVE'"
        + values
        + ")";
  }

  private static void exec(Connection c, String sql) throws java.sql.SQLException {
    try (var statement = c.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Liquibase liquibase(Connection c) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
  }

  /** The two tables as left by the changesets up to #1475, plus matrix_call_binding. */
  private static void createTablesAsBefore(Connection c) throws Exception {
    exec(c, "CREATE TABLE matrix_call_binding (id BIGINT PRIMARY KEY)");
    exec(c, "INSERT INTO matrix_call_binding (id) VALUES (1), (2)");
    exec(
        c,
        "CREATE TABLE agency_invite_link ("
            + " id BIGINT NOT NULL AUTO_INCREMENT,"
            + " token VARCHAR(64) NOT NULL UNIQUE,"
            + " tenant_id BIGINT NOT NULL,"
            + " topic_id BIGINT NULL,"
            + " link_kind VARCHAR(32) NOT NULL,"
            + " chat_type VARCHAR(32) NOT NULL,"
            + " anonymity VARCHAR(16) NOT NULL,"
            + " consultant_id VARCHAR(36) NULL,"
            + " agency_id BIGINT NULL,"
            + " created_by_user_id VARCHAR(36) NOT NULL,"
            + " create_date DATETIME NOT NULL,"
            + " expires_at DATETIME NULL,"
            + " status VARCHAR(20) NOT NULL,"
            + " name VARCHAR(100) NULL,"
            + " temporary_account BOOLEAN DEFAULT FALSE NOT NULL,"
            + " update_date DATETIME NULL,"
            + " PRIMARY KEY (id))");
    exec(
        c,
        "CREATE TABLE group_chat_join_request ("
            + " id BIGINT NOT NULL AUTO_INCREMENT,"
            + " series_id BIGINT UNSIGNED NULL,"
            + " consultant_id VARCHAR(36) NULL,"
            + " session_id BIGINT UNSIGNED NULL,"
            + " user_id VARCHAR(36) NULL,"
            + " invite_link_id BIGINT NULL,"
            + " medium VARCHAR(16) NULL,"
            + " status VARCHAR(16) NOT NULL,"
            + " via VARCHAR(32) NOT NULL DEFAULT 'INVITE_LINK',"
            + " requested_at DATETIME NOT NULL,"
            + " PRIMARY KEY (id))");
  }

  private static int count(Connection c, String table, String where) throws Exception {
    try (var sql = c.createStatement();
        var rs = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
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
