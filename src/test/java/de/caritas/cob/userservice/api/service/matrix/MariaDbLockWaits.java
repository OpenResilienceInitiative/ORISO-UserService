package de.caritas.cob.userservice.api.service.matrix;

import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Deterministic coordination for lock-contention ITs: waits until MariaDB reports that another
 * connection is blocked on a row lock, instead of sleeping and hoping the competitor got there.
 *
 * <p>Two signals count: an InnoDB transaction in {@code LOCK WAIT}, or a locking read ({@code FOR
 * UPDATE}) that has been executing for at least 300 ms. The second is needed because a {@code FOR
 * UPDATE} on a primary-key constant waits inside the optimizer ("Statistics" state), where MariaDB
 * 10.11 does not list the waiting transaction in INNODB_TRX. Reads both tables as root, because
 * they need the PROCESS privilege.
 */
final class MariaDbLockWaits {
  private static final String WAITING =
      "SELECT (SELECT COUNT(*) FROM information_schema.INNODB_TRX"
          + " WHERE trx_state = 'LOCK WAIT')"
          + " + (SELECT COUNT(*) FROM information_schema.PROCESSLIST"
          + " WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query' AND TIME_MS >= 300"
          + " AND LOWER(INFO) LIKE '%for update%')";

  private MariaDbLockWaits() {}

  static void awaitLockWait(MariaDBContainer<?> database) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (var connection =
        DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword())) {
      while (true) {
        try (var statement = connection.createStatement();
            var result = statement.executeQuery(WAITING)) {
          result.next();
          if (result.getLong(1) > 0) return;
        }
        if (System.nanoTime() > deadline) {
          throw new AssertionError("no connection is waiting on a row lock");
        }
        Thread.sleep(20);
      }
    }
  }
}
