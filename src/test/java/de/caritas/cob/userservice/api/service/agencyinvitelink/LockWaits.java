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
}
