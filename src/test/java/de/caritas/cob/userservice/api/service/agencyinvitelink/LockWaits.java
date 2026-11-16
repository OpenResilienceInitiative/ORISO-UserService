package de.caritas.cob.userservice.api.service.agencyinvitelink;

import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Concurrency tests of ORISO-Frontend#2252: waits until the database itself reports that one
 * transaction waits for another one's row lock (H2's {@code INFORMATION_SCHEMA.SESSIONS}), instead
 * of guessing with a sleep.
 */
final class LockWaits {

  private LockWaits() {}

  static void awaitALockWait(DataSource dataSource) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try (var connection = dataSource.getConnection();
          var sql = connection.createStatement();
          var rs =
              sql.executeQuery(
                  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL")) {
        rs.next();
        if (rs.getInt(1) > 0) {
          return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no transaction waited for a row lock");
  }

  /**
   * The H2 session of the transaction running on this thread: the connection Spring bound to it, so
   * a hook inside a repository call names the transaction it belongs to.
   */
  static int currentSession(DataSource dataSource) {
    var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
    try (var sql = connection.createStatement();
        var rs = sql.executeQuery("SELECT SESSION_ID()")) {
      rs.next();
      return rs.getInt(1);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    } finally {
      org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /** Waits until the database reports exactly this session blocked by exactly that one. */
  static void awaitBlockedBy(DataSource dataSource, int waiter, int blocker) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try (var connection = dataSource.getConnection();
          var sql =
              connection.prepareStatement(
                  "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"
                      + " WHERE SESSION_ID = ? AND BLOCKER_ID = ?")) {
        sql.setInt(1, waiter);
        sql.setInt(2, blocker);
        try (var rs = sql.executeQuery()) {
          rs.next();
          if (rs.getInt(1) > 0) {
            return;
          }
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("session " + waiter + " was never blocked by session " + blocker);
  }
}
