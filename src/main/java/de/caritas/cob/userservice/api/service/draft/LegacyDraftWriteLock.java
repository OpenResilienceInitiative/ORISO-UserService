package de.caritas.cob.userservice.api.service.draft;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.port.out.MariaDbNamedLock;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.ConnectionReleaseMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyDraftWriteLock {

  private static final int WAIT_SECONDS = 5;
  private final EntityManager entityManager;
  private final ReentrantLock h2Lock = new ReentrantLock();

  void acquireForCurrentTransaction(String userId) {
    var session = entityManager.unwrap(SessionImplementor.class);
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || session
                .getJdbcCoordinator()
                .getLogicalConnection()
                .getConnectionHandlingMode()
                .getReleaseMode()
            != ConnectionReleaseMode.ON_CLOSE) {
      throw new IllegalStateException(
          "Legacy draft creation requires a held transaction connection");
    }
    // JpaTransactionManager runs afterCompletion before closing an ON_CLOSE connection. Keeping
    // the advisory lock on the write connection avoids borrowing a second pool slot.
    session.doWork(
        connection -> {
          if ("H2".equals(connection.getMetaData().getDatabaseProductName())) {
            acquireLocalForH2();
          } else {
            acquireNamed(connection, lockName(userId));
          }
        });
  }

  private void acquireLocalForH2() {
    try {
      if (!h2Lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw busy();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw busy();
    }
    releaseAfterCompletion(h2Lock::unlock);
  }

  private void acquireNamed(Connection connection, String name) throws SQLException {
    try {
      if (!MariaDbNamedLock.tryAcquire(connection, name, WAIT_SECONDS)) {
        throw busy();
      }
    } catch (SQLException failedAcquire) {
      // The server may have accepted the lock before the driver reported an error.
      MariaDbNamedLock.abortQuietly(connection);
      throw failedAcquire;
    }
    releaseAfterCompletion(
        () -> {
          try {
            MariaDbNamedLock.release(connection, name);
          } catch (SQLException failedRelease) {
            MariaDbNamedLock.abortQuietly(connection);
            log.warn("Could not release legacy draft lock", failedRelease);
          }
        });
  }

  private static void releaseAfterCompletion(Runnable release) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            release.run();
          }
        });
  }

  static String lockName(String userId) {
    // Legacy scopes follow database collation, so raw hashes of equivalent scope keys could
    // diverge. Serialize the first insert by the authenticated owner, across all legacy scopes.
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
      return "oriso:draft:" + HexFormat.of().formatHex(digest, 0, 24);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static ServiceUnavailableException busy() {
    return new ServiceUnavailableException("Legacy draft is busy");
  }
}
