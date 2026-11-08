package de.caritas.cob.userservice.api.service.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class DraftProtectionSchemaTest {

  @Test
  void upgradeAndReapplyPreserveLegacyDuplicatesAndLargeCiphertext() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                "jdbc:h2:mem:drafts-" + UUID.randomUUID() + ";MODE=MariaDB");
        var sql = connection.createStatement()) {
      sql.execute(
          """
          CREATE TABLE draft_message (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id VARCHAR(64) NOT NULL,
            scope_key VARCHAR(255) NOT NULL,
            text TEXT
          )
          """);
      sql.execute(
          """
          INSERT INTO draft_message(user_id, scope_key, text)
          VALUES ('owner', 'legacy', 'first'), ('owner', 'legacy', 'second')
          """);
      var database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var liquibase =
          new Liquibase(
              "db/changelog/changeset/20261108_protected_draft_scope/changeSet.xml",
              new ClassLoaderResourceAccessor(),
              database)) {
        var contexts = new Contexts();
        var labels = new LabelExpression();
        liquibase.update(contexts, labels);
        String protectedScope = "draft.v1." + "a".repeat(43);
        try (var insert =
            connection.prepareStatement(
                """
                INSERT INTO draft_message(user_id, scope_key, protected_scope_key, text)
                VALUES ('owner', ?, ?, ?)
                """)) {
          insert.setString(1, protectedScope);
          insert.setString(2, protectedScope);
          insert.setString(3, "c".repeat(90_000));
          assertThat(insert.executeUpdate()).isOne();
          connection.commit();
          assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
        }

        liquibase.rollback(3, contexts, labels);
        liquibase.update(contexts, labels);
        try (var rows = sql.executeQuery("SELECT LENGTH(text) FROM draft_message ORDER BY id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(5);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(6);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(90_000);
          assertThat(rows.next()).isFalse();
        }
      }
    }
  }
}
