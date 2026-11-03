package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * On MariaDB/MySQL a named advisory lock ({@code GET_LOCK}) held on one pooled connection, so the
 * lock holds across replicas and a dead connection releases it. On any other database (the H2 of
 * the tests) a lock inside this JVM. Either way a lock inside this JVM is taken first, so requests
 * of one replica queue without each holding a database connection while they wait.
 */
@Component
@Slf4j
public class DatabaseMemberSetLock implements MemberSetLock {

  private static final int STRIPES = 64;
  private static final String NAME_PREFIX = "oriso:subset:";
  private static final int DEFAULT_MAX_CONNECTIONS = 8;

  private final DataSource dataSource;
  private final int waitSeconds;

  /**
   * Every holder keeps one pooled connection for the whole operation, and up to {@code STRIPES}
   * could hold one at once. The bound keeps the pool (50 by default) free for everything else; a
   * request that gets no place within the wait is answered busy.
   */
  private final Semaphore connections;

  private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
  private volatile Boolean advisoryLocks;

  public DatabaseMemberSetLock(DataSource dataSource, int waitSeconds) {
    this(dataSource, waitSeconds, DEFAULT_MAX_CONNECTIONS);
  }

  @Autowired
  public DatabaseMemberSetLock(
      DataSource dataSource,
      @Value("${internal-chat.member-set-lock.wait-seconds:15}") int waitSeconds,
      @Value("${internal-chat.member-set-lock.max-connections:8}") int maxConnections) {
    this.dataSource = dataSource;
    this.waitSeconds = waitSeconds;
    this.connections = new Semaphore(Math.max(1, maxConnections));
    for (int i = 0; i < STRIPES; i++) {
      stripes[i] = new ReentrantLock();
    }
  }

  @Override
  public Held acquire(Collection<String> memberIds) {
    var name = lockNameOf(memberIds);
    var stripe = stripes[Math.floorMod(name.hashCode(), STRIPES)];
    try {
      if (!stripe.tryLock(waitSeconds, TimeUnit.SECONDS)) {
        throw busy();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw busy();
    }
    Connection connection = null;
    var placeTaken = false;
    try {
      if (usesAdvisoryLocks()) {
        placeTaken = takePlace();
        connection = dataSource.getConnection();
        takeAdvisoryLock(connection, name);
      }
      var held = connection;
      return () -> {
        try {
          if (held != null) {
            releaseAdvisoryLock(held, name);
          }
        } finally {
          if (held != null) {
            connections.release();
          }
          stripe.unlock();
        }
      };
    } catch (SQLException | RuntimeException failure) {
      closeQuietly(connection);
      if (placeTaken) {
        connections.release();
      }
      stripe.unlock();
      if (failure instanceof CustomValidationHttpStatusException busy) {
        throw busy;
      }
      log.warn("The lock for a members-only room could not be taken: {}", failure.getMessage());
      throw busy();
    }
  }

  private boolean takePlace() {
    try {
      if (!connections.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
        throw busy();
      }
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw busy();
    }
  }

  /** The same people give the same name, whatever the order; 64 characters at most. */
  static String lockNameOf(Collection<String> memberIds) {
    var sorted = memberIds.stream().sorted().toList();
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
      return NAME_PREFIX + HexFormat.of().formatHex(digest).substring(0, 48);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean usesAdvisoryLocks() throws SQLException {
    var known = advisoryLocks;
    if (known == null) {
      try (var probe = dataSource.getConnection()) {
        var product = probe.getMetaData().getDatabaseProductName().toLowerCase();
        known = product.contains("mariadb") || product.contains("mysql");
      }
      advisoryLocks = known;
    }
    return known;
  }

  private void takeAdvisoryLock(Connection connection, String name) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
      statement.setString(1, name);
      statement.setInt(2, waitSeconds);
      try (var result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) {
          throw busy();
        }
      }
    }
  }

  private void releaseAdvisoryLock(Connection connection, String name) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, name);
      statement.executeQuery().close();
    } catch (SQLException failure) {
      log.warn("The lock for a members-only room could not be released: {}", failure.getMessage());
      // The lock belongs to this database session. If it is still held, handing the connection
      // back to the pool would keep it held (and other replicas waiting) until the pool retires
      // the connection. Ending the session releases it on the server.
      abortQuietly(connection);
    } finally {
      closeQuietly(connection);
    }
  }

  private static void abortQuietly(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException ignored) {
      // the connection is unusable either way; closeQuietly discards it
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException ignored) {
        // the pool discards a broken connection; its session locks go with it
      }
    }
  }

  private static CustomValidationHttpStatusException busy() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INTERNAL_CHAT_BUSY, HttpStatus.SERVICE_UNAVAILABLE);
  }
}
