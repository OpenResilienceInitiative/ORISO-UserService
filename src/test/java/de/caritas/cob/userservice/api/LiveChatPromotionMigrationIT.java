package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against a disposable MariaDB database supplied only for migration testing. */
@EnabledIfEnvironmentVariable(named = "GUEST_PROMOTION_TEST_DB_URL", matches = ".+")
class LiveChatPromotionMigrationIT {
  private static final String CHANGELOG =
      "db/changelog/changeset/20261105_live_chat_account_promotion/changeSet.xml";

  @Test
  void freshOriginalAndNewerHistoriesConvergeAndRollbackPreservesColumn() throws Exception {
    for (String history : new String[] {"fresh", "old", "new"}) {
      try (Connection c = connection()) {
        reset(c);
        if (!history.equals("fresh")) update(c, "src/test/resources/migration-history/" + history);
        update(c, "src/main/resources");
        update(c, "src/main/resources");
        assertThat(
                value(
                    c,
                    "SELECT CONCAT(column_type, ':', is_nullable, ':', column_default)"
                        + " FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='live_chat_account_claim' AND column_name='finished_origin'"))
            .isEqualTo("tinyint(1):NO:0");
        assertThat(
                value(
                    c,
                    "SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID='20261105-live-chat-finished-origin'"))
            .isEqualTo(history.equals("new") ? "MARK_RAN" : "EXECUTED");
        liquibase(c, "src/main/resources").rollback(1, (String) null);
        assertThat(
                value(
                    c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='live_chat_account_claim' AND column_name='finished_origin'"))
            .isEqualTo("1");
      }
    }
  }

  @Test
  void incompatibleExistingColumnIsRejected() throws Exception {
    for (String definition :
        new String[] {
          "tinyint(1) NOT NULL",
          "tinyint(1) NULL DEFAULT 0",
          "varchar(20) NOT NULL DEFAULT '0'",
          "tinyint(1) NOT NULL DEFAULT 1"
        }) {
      try (Connection c = connection()) {
        reset(c);
        update(c, "src/test/resources/migration-history/new");
        execute(c, "ALTER TABLE live_chat_account_claim MODIFY finished_origin " + definition);
        assertThatThrownBy(() -> update(c, "src/main/resources")).isInstanceOf(Exception.class);
      }
    }
  }

  private static Connection connection() throws Exception {
    String url = System.getenv("GUEST_PROMOTION_TEST_DB_URL");
    if (url == null || !url.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/migration_[a-z0-9_]+")) {
      throw new IllegalArgumentException(
          "Only a disposable local migration_* database is permitted");
    }
    return DriverManager.getConnection(
        url,
        System.getenv("GUEST_PROMOTION_TEST_DB_USER"),
        System.getenv("GUEST_PROMOTION_TEST_DB_PASSWORD"));
  }

  private static void reset(Connection c) throws Exception {
    execute(c, "DROP TABLE IF EXISTS live_chat_account_claim");
    execute(c, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
    execute(c, "DROP TABLE IF EXISTS DATABASECHANGELOG");
    execute(c, "DROP TABLE IF EXISTS user");
    execute(
        c,
        "CREATE TABLE user (user_id varchar(36) CHARACTER SET utf8 COLLATE utf8_unicode_ci PRIMARY KEY)");
  }

  private static void update(Connection c, String dir) throws Exception {
    liquibase(c, dir).update(new Contexts(), new LabelExpression());
  }

  private static Liquibase liquibase(Connection c, String dir) throws Exception {
    var db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    return new Liquibase(CHANGELOG, new DirectoryResourceAccessor(Path.of(dir)), db);
  }

  private static void execute(Connection c, String sql) throws Exception {
    try (var statement = c.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String value(Connection c, String sql) throws Exception {
    try (var statement = c.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }
}
