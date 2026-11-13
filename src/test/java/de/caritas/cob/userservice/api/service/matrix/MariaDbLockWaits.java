package de.caritas.cob.userservice.api.service.matrix;

import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Deterministic coordination for lock-contention ITs: waits until MariaDB reports that one
 * particular connection, the one of the operation under test, is blocked on a locking read of one
 * particular table, instead of sleeping and hoping the competitor got there (CodeRabbit 4562143209,
 * 4562502751, 4562504270).
 *
 * <p>The waiter must be in a {@code FOR UPDATE} on {@code table} and either listed as {@code LOCK
 * WAIT} in INNODB_TRX or executing for at least 300 ms. The second signal is needed because a
 * {@code FOR UPDATE} on a primary-key constant waits inside the optimizer ("Statistics" state),
 * where MariaDB 10.11 does not list the waiting transaction in INNODB_TRX. Reads both tables as
 * root, because they need the PROCESS privilege.
 */
final class MariaDbLockWaits {
  private static final String WAITING =
      "SELECT COUNT(*) FROM information_schema.PROCESSLIST p"
          + " WHERE p.ID = ? AND p.COMMAND = 'Query'"
          + " AND LOWER(p.INFO) LIKE '%for update%' AND LOWER(p.INFO) LIKE ?"
          + " AND (p.TIME_MS >= 300 OR EXISTS (SELECT 1 FROM information_schema.INNODB_TRX t"
          + " WHERE t.trx_mysql_thread_id = p.ID AND t.trx_state = 'LOCK WAIT'))";

  private MariaDbLockWaits() {}

  /**
   * Waits until the connection {@code waiter} names is blocked in a {@code FOR UPDATE} on {@code
   * table}. {@code waiter} may answer {@code null} until the operation's transaction has begun.
   */
  static void awaitLockWait(MariaDBContainer<?> database, Supplier<Long> waiter, String table)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (var connection =
            DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword());
        var statement = connection.prepareStatement(WAITING)) {
      while (true) {
        Long id = waiter.get();
        if (id != null) {
          statement.setLong(1, id);
          statement.setString(2, "%from " + table.toLowerCase() + " %");
          try (var result = statement.executeQuery()) {
            result.next();
            if (result.getLong(1) > 0) return;
          }
        }
        if (System.nanoTime() > deadline) {
          throw new AssertionError(
              "connection " + id + " is not waiting on a row lock of " + table);
        }
        Thread.sleep(20);
      }
    }
  }

  /**
   * Records, per thread, the MariaDB connection of the transaction it began last. Runs {@code
   * SELECT CONNECTION_ID()} right after the begin, on the transaction's own connection; that
   * statement reads no InnoDB table, so it neither opens a read view nor takes a lock, and the
   * operation under test runs unchanged (its own transactions and retries included).
   */
  static final class TransactionConnections implements TransactionExecutionListener, AutoCloseable {
    private final ConfigurableTransactionManager transactionManager;
    private final JdbcTemplate jdbc;
    private final Map<Thread, Long> latest = new ConcurrentHashMap<>();

    private TransactionConnections(PlatformTransactionManager manager, DataSource dataSource) {
      this.transactionManager = (ConfigurableTransactionManager) manager;
      this.jdbc = new JdbcTemplate(dataSource);
    }

    static TransactionConnections watch(PlatformTransactionManager manager, DataSource dataSource) {
      var connections = new TransactionConnections(manager, dataSource);
      connections.transactionManager.addListener(connections);
      return connections;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
      if (beginFailure != null) return;
      latest.put(Thread.currentThread(), jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }

    /** The connection {@code thread}'s latest transaction runs on, or null before it began. */
    Supplier<Long> of(java.util.concurrent.atomic.AtomicReference<Thread> thread) {
      return () -> thread.get() == null ? null : latest.get(thread.get());
    }

    @Override
    public void close() {
      transactionManager.getTransactionExecutionListeners().remove(this);
    }
  }
}
