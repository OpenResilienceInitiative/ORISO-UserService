package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;

/**
 * #2237 on MariaDB: the session_id changeset is safe on a database that already has the column
 * (master changelog rule), the chat_id nullability it also carries is still applied there by the
 * follow-up, and the changeset keeps the checksum it ran with on Dev.
 */
class CallObservationSessionMigrationMariaDbIT {

  private static final String SESSION_2237 =
      "db/changelog/changeset/20261112_call_observation_session_2237/changeSet.xml";
  private static final String CHAT_NULLABLE_2237 =
      "db/changelog/changeset/20261113_call_observation_chat_nullable_2237/changeSet.xml";

  /** Liquibase 4.27 checksum of the changeset as merged to dev (bf6a3209f) and run on Dev. */
  private static final String CHECKSUM_ON_DEV = "9:135d1aa1ce2e13ed4645f36997610ae2";

  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @Test
  void theChangesetKeepsTheChecksumItRanWithOnDev() throws Exception {
    var accessor = new ClassLoaderResourceAccessor();
    var changeLog =
        ChangeLogParserFactory.getInstance()
            .getParser(SESSION_2237, accessor)
            .parse(SESSION_2237, new ChangeLogParameters(), accessor);
    ChangeSet changeSet = changeLog.getChangeSets().get(0);
    assertThat(changeSet.generateCheckSum(liquibase.ChecksumVersion.V9).toString())
        .isEqualTo(CHECKSUM_ON_DEV);
  }

  @Test
  void aColumnThatIsAlreadyThereIsMarkedRanAndChatIdStillBecomesNullable() throws Exception {
    try (Connection c = connect()) {
      reset(c, true);
      update(c, SESSION_2237);
      update(c, CHAT_NULLABLE_2237);

      assertThat(execType(c, "20261112-matrix-call-observation-session-2237"))
          .isEqualTo("MARK_RAN");
      assertThat(nullable(c, "chat_id")).isTrue();
      assertThat(nullable(c, "session_id")).isTrue();
    }
  }

  @Test
  void onAFreshTableBothRunAndTheFollowUpIsMarkedRan() throws Exception {
    try (Connection c = connect()) {
      reset(c, false);
      update(c, SESSION_2237);
      update(c, CHAT_NULLABLE_2237);

      assertThat(execType(c, "20261112-matrix-call-observation-session-2237"))
          .isEqualTo("EXECUTED");
      assertThat(execType(c, "20261113-matrix-call-observation-chat-nullable-2237"))
          .isEqualTo("MARK_RAN");
      assertThat(nullable(c, "chat_id")).isTrue();
      assertThat(nullable(c, "session_id")).isTrue();
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  /** The table as #2166 created it, optionally with session_id already added by hand. */
  private static void reset(Connection c, boolean sessionIdByHand) throws SQLException {
    exec(c, "DROP TABLE IF EXISTS matrix_call_observation");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOG");
    exec(c, "DROP TABLE IF EXISTS DATABASECHANGELOGLOCK");
    exec(
        c,
        "CREATE TABLE matrix_call_observation (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + " origin VARCHAR(40) NOT NULL, source_room_id VARCHAR(255) NOT NULL,"
            + " call_id VARCHAR(191) NOT NULL, media_room_id VARCHAR(255) NOT NULL,"
            + " creator_matrix_id VARCHAR(255) NOT NULL, tenant_id BIGINT NOT NULL,"
            + " chat_id BIGINT NOT NULL, video BOOLEAN NOT NULL, registered_at BIGINT NOT NULL,"
            + " invited_at BIGINT, last_seen_at BIGINT NOT NULL, expires_at BIGINT NOT NULL,"
            + " ended_at BIGINT"
            + (sessionIdByHand ? ", session_id BIGINT" : "")
            + ")");
  }

  private static void update(Connection c, String changeLog) throws Exception {
    var database =
        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    var liquibase = new Liquibase(changeLog, new ClassLoaderResourceAccessor(), database);
    liquibase.update(new Contexts(), new LabelExpression());
    if (!c.getAutoCommit()) c.commit();
  }

  private static boolean nullable(Connection c, String column) throws SQLException {
    try (var sql =
        c.prepareStatement(
            "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 'matrix_call_observation' AND COLUMN_NAME = ?")) {
      sql.setString(1, column);
      try (var rs = sql.executeQuery()) {
        rs.next();
        return "YES".equals(rs.getString(1));
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
