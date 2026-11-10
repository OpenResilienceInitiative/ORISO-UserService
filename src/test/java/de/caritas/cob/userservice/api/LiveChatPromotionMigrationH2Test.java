package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;

class LiveChatPromotionMigrationH2Test {
  @Test
  void freshDatabaseMigrates() throws Exception {
    try (var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;NON_KEYWORDS=USER", "sa", "")) {
      c.createStatement().execute("CREATE TABLE user (user_id varchar(36) PRIMARY KEY)");
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      var migration =
          new Liquibase(
              "db/changelog/changeset/20261105_live_chat_account_promotion/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              db);
      migration.update(new Contexts(), new LabelExpression());
      try (var result =
          c.createStatement()
              .executeQuery(
                  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='LIVE_CHAT_ACCOUNT_CLAIM' AND COLUMN_NAME='FINISHED_ORIGIN'")) {
        result.next();
        assertThat(result.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void previouslyPublishedNewerHistoryRemainsCompatible() throws Exception {
    try (var c =
        DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MariaDB;NON_KEYWORDS=USER", "sa", "")) {
      c.createStatement().execute("CREATE TABLE user (user_id varchar(36) PRIMARY KEY)");
      var db =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      var file = "db/changelog/changeset/20261105_live_chat_account_promotion/changeSet.xml";
      var prior =
          new Liquibase(
              file,
              new DirectoryResourceAccessor(Path.of("src/test/resources/migration-history/new")),
              db);
      prior.update(new Contexts(), new LabelExpression());
      var current = new Liquibase(file, new ClassLoaderResourceAccessor(), db);
      current.update(new Contexts(), new LabelExpression());
      current.update(new Contexts(), new LabelExpression());
      try (var result =
          c.createStatement()
              .executeQuery(
                  "SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID='20261105-live-chat-finished-origin'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("MARK_RAN");
      }
    }
  }
}
